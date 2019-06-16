/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.controller.api.v2.user;

import alfio.controller.api.v2.model.*;
import alfio.controller.api.v2.model.AdditionalService;
import alfio.controller.api.v2.model.EventWithAdditionalInfo;
import alfio.controller.api.v2.model.EventWithAdditionalInfo.PaymentProxyWithParameters;
import alfio.controller.api.v2.model.TicketCategory;
import alfio.controller.decorator.SaleableAdditionalService;
import alfio.controller.decorator.SaleableTicketCategory;
import alfio.controller.form.ReservationForm;
import alfio.controller.form.WaitingQueueSubscriptionForm;
import alfio.controller.support.Formatters;
import alfio.controller.support.SessionUtil;
import alfio.manager.*;
import alfio.manager.i18n.I18nManager;
import alfio.manager.system.ConfigurationManager;
import alfio.model.*;
import alfio.model.modification.support.LocationDescriptor;
import alfio.model.result.ValidationResult;
import alfio.model.system.Configuration;
import alfio.model.system.ConfigurationKeys;
import alfio.model.transaction.PaymentMethod;
import alfio.model.transaction.PaymentProxy;
import alfio.repository.*;
import alfio.repository.user.OrganizationRepository;
import alfio.util.*;
import lombok.AllArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.ServletWebRequest;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static alfio.model.PromoCodeDiscount.categoriesOrNull;
import static alfio.model.system.Configuration.getSystemConfiguration;
import static alfio.model.system.ConfigurationKeys.*;
import static java.util.stream.Collectors.toList;


@RestController
@RequestMapping("/api/v2/public/")
@AllArgsConstructor
public class EventApiV2Controller {

    private final EventManager eventManager;
    private final EventRepository eventRepository;
    private final ConfigurationManager configurationManager;
    private final OrganizationRepository organizationRepository;
    private final EventDescriptionRepository eventDescriptionRepository;
    private final TicketCategoryDescriptionRepository ticketCategoryDescriptionRepository;
    private final PaymentManager paymentManager;
    private final CustomResourceBundleMessageSource messageSource;
    private final EuVatChecker vatChecker;
    private final AdditionalServiceRepository additionalServiceRepository;
    private final AdditionalServiceTextRepository additionalServiceTextRepository;
    private final WaitingQueueManager waitingQueueManager;
    private final I18nManager i18nManager;
    private final TicketCategoryRepository ticketCategoryRepository;
    private final TicketRepository ticketRepository;
    private final TicketReservationManager ticketReservationManager;
    private final PromoCodeDiscountRepository promoCodeRepository;
    private final EventStatisticsManager eventStatisticsManager;
    private final RecaptchaService recaptchaService;
    private final PromoCodeDiscountRepository promoCodeDiscountRepository;
    private final SpecialPriceRepository specialPriceRepository;


    @GetMapping("events")
    public ResponseEntity<List<BasicEventInfo>> listEvents() {

        var langs = i18nManager.getSupportedLanguages();

        var events = eventManager.getPublishedEvents()
            .stream()
            .map(e -> {
                var formattedBeginDate = Formatters.getFormattedDate(langs, e.getBegin(), "common.event.date-format", messageSource);
                var formattedBeginTime = Formatters.getFormattedDate(langs, e.getBegin(), "common.event.time-format", messageSource);
                var formattedEndDate = Formatters.getFormattedDate(langs, e.getEnd(), "common.event.date-format", messageSource);
                var formattedEndTime = Formatters.getFormattedDate(langs, e.getEnd(), "common.event.time-format", messageSource);
                return new BasicEventInfo(e.getShortName(), e.getFileBlobId(), e.getDisplayName(), e.getLocation(),
                    e.getTimeZone(), e.getSameDay(), formattedBeginDate, formattedBeginTime, formattedEndDate, formattedEndTime);
            })
            .collect(Collectors.toList());
        return new ResponseEntity<>(events, getCorsHeaders(), HttpStatus.OK);
    }

    @GetMapping("event/{eventName}")
    public ResponseEntity<EventWithAdditionalInfo> getEvent(@PathVariable("eventName") String eventName) {
        return eventRepository.findOptionalByShortName(eventName).filter(e -> e.getStatus() != Event.Status.DISABLED)//
            .map(event -> {

                var descriptions = applyCommonMark(eventDescriptionRepository.findByEventIdAndType(event.getId(), EventDescription.EventDescriptionType.DESCRIPTION)
                    .stream()
                    .collect(Collectors.toMap(EventDescription::getLocale, EventDescription::getDescription)));

                var organization = organizationRepository.getById(event.getOrganizationId());

                Map<ConfigurationKeys, Optional<String>> geoInfoConfiguration = configurationManager.getStringConfigValueFrom(
                    Configuration.from(event, ConfigurationKeys.MAPS_PROVIDER),
                    Configuration.from(event, ConfigurationKeys.MAPS_CLIENT_API_KEY),
                    Configuration.from(event, ConfigurationKeys.MAPS_HERE_APP_ID),
                    Configuration.from(event, ConfigurationKeys.MAPS_HERE_APP_CODE));

                var ld = LocationDescriptor.fromGeoData(event.getLatLong(), TimeZone.getTimeZone(event.getTimeZone()), geoInfoConfiguration);

                Map<PaymentMethod, PaymentProxyWithParameters> availablePaymentMethods = new EnumMap<>(PaymentMethod.class);

                var activePaymentMethods = getActivePaymentMethods(event);

                activePaymentMethods.forEach(apm -> {
                    availablePaymentMethods.put(apm.getPaymentMethod(), new PaymentProxyWithParameters(apm, paymentManager.loadModelOptionsFor(Collections.singletonList(apm), event)));
                });

                //
                boolean captchaForTicketSelection = configurationManager.isRecaptchaForTicketSelectionEnabled(event);
                String recaptchaApiKey = null;
                if (captchaForTicketSelection) {
                    recaptchaApiKey = configurationManager.getStringConfigValue(getSystemConfiguration(RECAPTCHA_API_KEY), null);
                }
                //
                var captchaConf = new EventWithAdditionalInfo.CaptchaConfiguration(captchaForTicketSelection, recaptchaApiKey);


                //
                String bankAccount = configurationManager.getStringConfigValue(Configuration.from(event, BANK_ACCOUNT_NR)).orElse("");
                List<String> bankAccountOwner = Arrays.asList(configurationManager.getStringConfigValue(Configuration.from(event, BANK_ACCOUNT_OWNER)).orElse("").split("\n"));
                //

                var formattedBeginDate = Formatters.getFormattedDate(event, event.getBegin(), "common.event.date-format", messageSource);
                var formattedBeginTime = Formatters.getFormattedDate(event, event.getBegin(), "common.event.time-format", messageSource);
                var formattedEndDate = Formatters.getFormattedDate(event, event.getEnd(), "common.event.date-format", messageSource);
                var formattedEndTime = Formatters.getFormattedDate(event, event.getEnd(), "common.event.time-format", messageSource);


                var partialConfig = Configuration.from(event);

                //invoicing information
                boolean canGenerateReceiptOrInvoiceToCustomer = configurationManager.canGenerateReceiptOrInvoiceToCustomer(event);
                boolean euVatCheckingEnabled = vatChecker.isReverseChargeEnabledFor(event.getOrganizationId());
                boolean invoiceAllowed = configurationManager.hasAllConfigurationsForInvoice(event) || euVatCheckingEnabled;
                boolean onlyInvoice = invoiceAllowed && configurationManager.isInvoiceOnly(event);
                boolean customerReferenceEnabled = configurationManager.getBooleanConfigValue(partialConfig.apply(ENABLE_CUSTOMER_REFERENCE), false);
                boolean enabledItalyEInvoicing = configurationManager.getBooleanConfigValue(partialConfig.apply(ENABLE_ITALY_E_INVOICING), false);
                boolean vatNumberStrictlyRequired = configurationManager.getBooleanConfigValue(partialConfig.apply(VAT_NUMBER_IS_REQUIRED), false);

                var invoicingConf = new EventWithAdditionalInfo.InvoicingConfiguration(canGenerateReceiptOrInvoiceToCustomer,
                    euVatCheckingEnabled, invoiceAllowed, onlyInvoice,
                    customerReferenceEnabled, enabledItalyEInvoicing, vatNumberStrictlyRequired);
                //

                //
                boolean forceAssignment = configurationManager.getBooleanConfigValue(partialConfig.apply(FORCE_TICKET_OWNER_ASSIGNMENT_AT_RESERVATION), false);
                boolean enableAttendeeAutocomplete = configurationManager.getBooleanConfigValue(partialConfig.apply(ENABLE_ATTENDEE_AUTOCOMPLETE), true);
                boolean enableTicketTransfer = configurationManager.getBooleanConfigValue(Configuration.from(event).apply(ENABLE_TICKET_TRANSFER), true);
                var assignmentConf = new EventWithAdditionalInfo.AssignmentConfiguration(forceAssignment, enableAttendeeAutocomplete, enableTicketTransfer);
                //


                //promotion codes
                boolean hasAccessPromotions = configurationManager.getBooleanConfigValue(Configuration.from(event, ConfigurationKeys.DISPLAY_DISCOUNT_CODE_BOX), true) &&
                    (ticketCategoryRepository.countAccessRestrictedRepositoryByEventId(event.getId()) > 0 ||
                        promoCodeDiscountRepository.countByEventAndOrganizationId(event.getId(), event.getOrganizationId()) > 0);
                boolean usePartnerCode = configurationManager.getBooleanConfigValue(Configuration.from(event, ConfigurationKeys.USE_PARTNER_CODE_INSTEAD_OF_PROMOTIONAL), false);
                var promoConf = new EventWithAdditionalInfo.PromotionsConfiguration(hasAccessPromotions, usePartnerCode);
                //

                return new ResponseEntity<>(new EventWithAdditionalInfo(event, ld.getMapUrl(), organization, descriptions, availablePaymentMethods,
                    bankAccount, bankAccountOwner,
                    formattedBeginDate, formattedBeginTime,
                    formattedEndDate, formattedEndTime, invoicingConf, captchaConf, assignmentConf, promoConf), getCorsHeaders(), HttpStatus.OK);
            })
            .orElseGet(() -> ResponseEntity.notFound().headers(getCorsHeaders()).build());
    }

    private List<PaymentProxy> getActivePaymentMethods(Event event) {
        if(!event.isFreeOfCharge()) {
            return paymentManager.getPaymentMethods(event)
                .stream()
                .filter(p -> TicketReservationManager.isValidPaymentMethod(p, event, configurationManager))
                .map(PaymentManager.PaymentMethodDTO::getPaymentProxy)
                .collect(toList());
        } else {
            return Collections.emptyList();
        }
    }

    private static Map<String, String> applyCommonMark(Map<String, String> in) {
        if (in == null) {
            return Collections.emptyMap();
        }

        var res = new HashMap<String, String>();
        in.forEach((k, v) -> {
            res.put(k, MustacheCustomTagInterceptor.renderToCommonmark(v));
        });
        return res;
    }

    @PostMapping("event/{eventName}/waiting-list/subscribe")
    public ResponseEntity<ValidatedResponse<Boolean>> subscribeToWaitingList(@PathVariable("eventName") String eventName,
                                                                             @RequestBody WaitingQueueSubscriptionForm subscription,
                                                                             BindingResult bindingResult) {

        Optional<ResponseEntity<ValidatedResponse<Boolean>>> res = eventRepository.findOptionalByShortName(eventName).map(event -> {
            Validator.validateWaitingQueueSubscription(subscription, bindingResult, event);
            if (bindingResult.hasErrors()) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(ValidatedResponse.toResponse(bindingResult, null));
            } else {
                var subscriptionResult = waitingQueueManager.subscribe(event, subscription.toCustomerName(event), subscription.getEmail(), subscription.getSelectedCategory(), subscription.getUserLanguage());
                return ResponseEntity.ok(new ValidatedResponse<>(ValidationResult.success(), subscriptionResult));
            }
        });

        return res.orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("event/{eventName}/ticket-categories")
    public ResponseEntity<ItemsByCategory> getTicketCategories(@PathVariable("eventName") String eventName, @RequestParam(value = "code", required = false) String code) {

        //
        return eventRepository.findOptionalByShortName(eventName).filter(e -> e.getStatus() != Event.Status.DISABLED).map(event -> {


            var appliedPromoCode = checkCode(event, code);


            Optional<SpecialPrice> specialCode = appliedPromoCode.getValue().getLeft();
            Optional<PromoCodeDiscount> promoCodeDiscount = appliedPromoCode.getValue().getRight();

            final ZonedDateTime now = ZonedDateTime.now(event.getZoneId());
            //hide access restricted ticket categories
            var ticketCategories = ticketCategoryRepository.findAllTicketCategories(event.getId());

            List<SaleableTicketCategory> saleableTicketCategories = ticketCategories.stream()
                .filter((c) -> !c.isAccessRestricted() || shouldDisplayRestrictedCategory(specialCode, c, promoCodeDiscount))
                .map((m) -> {
                    int maxTickets = configurationManager.getIntConfigValue(Configuration.from(event.getOrganizationId(), event.getId(), m.getId(), ConfigurationKeys.MAX_AMOUNT_OF_TICKETS_BY_RESERVATION), 5);
                    PromoCodeDiscount filteredPromoCode = promoCodeDiscount.filter(promoCode -> shouldApplyDiscount(promoCode, m)).orElse(null);
                    if (specialCode.isPresent()) {
                        maxTickets = Math.min(1, maxTickets);
                    } else if (filteredPromoCode != null && filteredPromoCode.getMaxUsage() != null) {
                        maxTickets = filteredPromoCode.getMaxUsage() - promoCodeRepository.countConfirmedPromoCode(filteredPromoCode.getId(), categoriesOrNull(filteredPromoCode), null, categoriesOrNull(filteredPromoCode) != null ? "X" : null);
                    }
                    return new SaleableTicketCategory(m,
                        now, event, ticketReservationManager.countAvailableTickets(event, m), maxTickets,
                        filteredPromoCode);
                })
                .collect(Collectors.toList());


            var valid = saleableTicketCategories.stream().filter(tc -> !tc.getExpired()).collect(Collectors.toList());

            //

            var ticketCategoryIds = valid.stream().map(SaleableTicketCategory::getId).collect(Collectors.toList());
            var ticketCategoryDescriptions = ticketCategoryDescriptionRepository.descriptionsByTicketCategory(ticketCategoryIds);


            var converted = valid.stream()
                .map(stc -> {
                    var description = applyCommonMark(ticketCategoryDescriptions.getOrDefault(stc.getId(), Collections.emptyMap()));
                    var expiration = Formatters.getFormattedDate(event, stc.getZonedExpiration(), "common.ticket-category.date-format", messageSource);
                    var inception = Formatters.getFormattedDate(event, stc.getZonedInception(), "common.ticket-category.date-format", messageSource);
                    return new TicketCategory(stc, description, inception, expiration);
                })
                .collect(Collectors.toList());


            var promoCode = Optional.of(appliedPromoCode).filter(ValidatedResponse::isSuccess)
                .map(ValidatedResponse::getValue)
                .map(Pair::getRight)
                .orElse(Optional.empty());

            //
            var saleableAdditionalServices = additionalServiceRepository.loadAllForEvent(event.getId())
                .stream()
                .map(as -> new SaleableAdditionalService(event, as, promoCode.orElse(null)))
                .filter(SaleableAdditionalService::isNotExpired)
                .collect(Collectors.toList());

            // will be used for fetching descriptions and titles for all the languages
            var saleableAdditionalServicesIds = saleableAdditionalServices.stream().map(as -> as.getId()).collect(Collectors.toList());

            var additionalServiceTexts = additionalServiceTextRepository.getDescriptionsByAdditionalServiceIds(saleableAdditionalServicesIds);

            var additionalServicesRes = saleableAdditionalServices.stream().map(as -> {
                var expiration = Formatters.getFormattedDate(event, as.getZonedExpiration(), "common.ticket-category.date-format", messageSource);
                var inception = Formatters.getFormattedDate(event, as.getZonedInception(), "common.ticket-category.date-format", messageSource);
                var title = additionalServiceTexts.getOrDefault(as.getId(), Collections.emptyMap()).getOrDefault(AdditionalServiceText.TextType.TITLE, Collections.emptyMap());
                var description = applyCommonMark(additionalServiceTexts.getOrDefault(as.getId(), Collections.emptyMap()).getOrDefault(AdditionalServiceText.TextType.DESCRIPTION, Collections.emptyMap()));
                return new AdditionalService(as.getId(), as.getType(), as.getSupplementPolicy(),
                    as.isFixPrice(), as.getAvailableQuantity(), as.getMaxQtyPerOrder(),
                    as.getFree(), as.getFormattedFinalPrice(), as.getSupportsDiscount(), as.getDiscountedPrice(), as.getVatApplies(), as.getVatIncluded(), as.getVatPercentage(),
                    as.isExpired(), as.getSaleInFuture(),
                    inception, expiration, title, description);
            }).collect(Collectors.toList());
            //

            // waiting queue parameters
            boolean displayWaitingQueueForm = EventUtil.displayWaitingQueueForm(event, saleableTicketCategories, configurationManager, eventStatisticsManager.noSeatsAvailable());
            boolean preSales = EventUtil.isPreSales(event, saleableTicketCategories);
            Predicate<SaleableTicketCategory> waitingQueueTargetCategory = tc -> !tc.getExpired() && !tc.isBounded();
            List<SaleableTicketCategory> unboundedCategories = saleableTicketCategories.stream().filter(waitingQueueTargetCategory).collect(Collectors.toList());
            var tcForWaitingList = unboundedCategories.stream().map(stc -> new ItemsByCategory.TicketCategoryForWaitingList(stc.getId(), stc.getName())).collect(toList());
            //

            return new ResponseEntity<>(new ItemsByCategory(converted, additionalServicesRes, displayWaitingQueueForm, preSales, tcForWaitingList), getCorsHeaders(), HttpStatus.OK);
        }).orElseGet(() -> {
            return ResponseEntity.notFound().headers(getCorsHeaders()).build();
        });
    }

    @GetMapping("event/{eventName}/languages")
    public ResponseEntity<List<String>> getLanguages(@PathVariable("eventName") String eventName) {

        var languages = i18nManager.getEventLanguages(eventName)
            .stream()
            .map(cl -> cl.getLocale().getLanguage())
            .collect(Collectors.toList());

        if (languages.isEmpty()) {
            return ResponseEntity.notFound().build();
        } else {
            return ResponseEntity.ok(languages);
        }
    }

    @GetMapping("event/{eventName}/calendar/{locale}")
    public void getCalendar(@PathVariable("eventName") String eventName,
                            @PathVariable("locale") String locale,
                            @RequestParam(value = "type", required = false) String calendarType,
                            @RequestParam(value = "ticketId", required = false) String ticketId,
                            HttpServletResponse response) {

        eventRepository.findOptionalByShortName(eventName).ifPresentOrElse((ev -> {
            var description = eventDescriptionRepository.findDescriptionByEventIdTypeAndLocale(ev.getId(), EventDescription.EventDescriptionType.DESCRIPTION, locale).orElse("");
            var category = ticketRepository.findOptionalByUUID(ticketId).map(t -> ticketCategoryRepository.getById(t.getCategoryId())).orElse(null);
            if ("google".equals(calendarType)) {
                try {
                    response.sendRedirect(EventUtil.getGoogleCalendarURL(ev, category, description));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            } else {
                EventUtil.getIcalForEvent(ev, category, description).ifPresentOrElse(ical -> {
                    response.setContentType("text/calendar");
                    response.setHeader("Content-Disposition", "inline; filename=\"calendar.ics\"");
                    try (var os = response.getOutputStream()){
                        os.write(ical);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }, () -> {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                });
            }
        }), () -> response.setStatus(HttpServletResponse.SC_NOT_FOUND));
    }

    @PostMapping(value = "event/{eventName}/reserve-tickets")
    public ResponseEntity<ValidatedResponse<String>> reserveTicket(@PathVariable("eventName") String eventName,
                                                                   @RequestParam("lang") String lang,
                                                                   @RequestBody ReservationForm reservation,
                                                                   BindingResult bindingResult,
                                                                   ServletWebRequest request) {



        Optional<ResponseEntity<ValidatedResponse<String>>> r = eventRepository.findOptionalByShortName(eventName).map(event -> {


            Optional<ValidatedResponse<Pair<Optional<SpecialPrice>, Optional<PromoCodeDiscount>>>> codeCheck = Optional.empty();

            if(StringUtils.trimToNull(reservation.getPromoCode()) != null) {
                var resCheck = checkCode(event, reservation.getPromoCode());
                if(!resCheck.isSuccess()) {
                    bindingResult.reject(ErrorsCode.STEP_1_CODE_NOT_FOUND, ErrorsCode.STEP_1_CODE_NOT_FOUND);
                }
                codeCheck = Optional.of(resCheck);
            }
            Optional<String> specialPrice = codeCheck.map(ValidatedResponse::getValue).flatMap(Pair::getLeft).map(SpecialPrice::getCode);
            Optional<String> promoCodeDiscount = codeCheck.map(ValidatedResponse::getValue).flatMap(Pair::getRight).map(PromoCodeDiscount::getPromoCode);


            if (isCaptchaInvalid(reservation.getCaptcha(), request.getRequest(), event)) {
                bindingResult.reject(ErrorsCode.STEP_2_CAPTCHA_VALIDATION_FAILED);
            }

            var reservationIdRes = reservation.validate(bindingResult, ticketReservationManager, additionalServiceRepository, eventManager, event).flatMap(selected -> {
                Date expiration = DateUtils.addMinutes(new Date(), ticketReservationManager.getReservationTimeout(event));
                try {
                    String reservationId = ticketReservationManager.createTicketReservation(event,
                        selected.getLeft(), selected.getRight(), expiration,
                        specialPrice,
                        promoCodeDiscount,
                        Locale.forLanguageTag(lang), false);
                    return Optional.of(reservationId);
                } catch (TicketReservationManager.NotEnoughTicketsException nete) {
                    bindingResult.reject(ErrorsCode.STEP_1_NOT_ENOUGH_TICKETS);
                } catch (TicketReservationManager.MissingSpecialPriceTokenException missing) {
                    bindingResult.reject(ErrorsCode.STEP_1_ACCESS_RESTRICTED);
                } catch (TicketReservationManager.InvalidSpecialPriceTokenException invalid) {
                    bindingResult.reject(ErrorsCode.STEP_1_CODE_NOT_FOUND);
                    SessionUtil.cleanupSession(request.getRequest());
                } catch (TicketReservationManager.TooManyTicketsForDiscountCodeException tooMany) {
                    bindingResult.reject(ErrorsCode.STEP_2_DISCOUNT_CODE_USAGE_EXCEEDED);
                }

                return Optional.empty();
            });

            if (bindingResult.hasErrors()) {
                return new ResponseEntity<>(ValidatedResponse.toResponse(bindingResult, (String) null), getCorsHeaders(), HttpStatus.UNPROCESSABLE_ENTITY);
            } else {
                var reservationIdentifier = reservationIdRes.orElseThrow(IllegalStateException::new);
                return ResponseEntity.ok(new ValidatedResponse<>(ValidationResult.success(), reservationIdentifier));
            }
        });

        return r.orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "event/{eventName}/validate-code")
    public ResponseEntity<ValidatedResponse<EventCode>> validateCode(@PathVariable("eventName") String eventName,
                                                                   @RequestParam("code") String code) {

        return eventRepository.findOptionalEventAndOrganizationIdByShortName(eventName).map(e -> {
            var res = checkCode(e, code);
            if(res.isSuccess()) {

                var eventCode = res.getValue().getLeft()
                    .map(sp -> new EventCode(code, EventCode.EventCodeType.SPECIAL_PRICE, PromoCodeDiscount.DiscountType.NONE, null))
                    .orElseGet(() -> {
                        var promoCodeDiscount = res.getValue().getRight().orElseThrow();
                        var type = promoCodeDiscount.getCodeType() == PromoCodeDiscount.CodeType.ACCESS ? EventCode.EventCodeType.ACCESS : EventCode.EventCodeType.DISCOUNT;
                        String formattedDiscountAmount =  promoCodeDiscount.getDiscountType() == PromoCodeDiscount.DiscountType.FIXED_AMOUNT ? promoCodeDiscount.getFormattedDiscountAmount().toString() : Integer.toString(promoCodeDiscount.getDiscountAmount());
                        return new EventCode(code, type, promoCodeDiscount.getDiscountType(), formattedDiscountAmount);
                    });

                return ResponseEntity.ok(res.withValue(eventCode));
            } else {
                return new ResponseEntity<>(res.withValue(new EventCode(code,null, null, null)), HttpStatus.UNPROCESSABLE_ENTITY);
            }
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }


    private boolean shouldDisplayRestrictedCategory(Optional<SpecialPrice> specialCode, alfio.model.TicketCategory c, Optional<PromoCodeDiscount> optionalPromoCode) {
        if(optionalPromoCode.isPresent()) {
            var promoCode = optionalPromoCode.get();
            if(promoCode.getCodeType() == PromoCodeDiscount.CodeType.ACCESS && c.getId() == promoCode.getHiddenCategoryId()) {
                return true;
            }
        }
        return specialCode.filter(sc -> sc.getTicketCategoryId() == c.getId()).isPresent();
    }

    private static boolean shouldApplyDiscount(PromoCodeDiscount promoCodeDiscount, alfio.model.TicketCategory ticketCategory) {
        if(promoCodeDiscount.getCodeType() == PromoCodeDiscount.CodeType.DISCOUNT) {
            return promoCodeDiscount.getCategories().isEmpty() || promoCodeDiscount.getCategories().contains(ticketCategory.getId());
        }
        return ticketCategory.isAccessRestricted() && ticketCategory.getId() == promoCodeDiscount.getHiddenCategoryId();
    }

    private ValidatedResponse<Pair<Optional<SpecialPrice>, Optional<PromoCodeDiscount>>> checkCode(EventAndOrganizationId event, String promoCode) {
        ZoneId eventZoneId = eventRepository.getZoneIdByEventId(event.getId());
        ZonedDateTime now = ZonedDateTime.now(eventZoneId);
        Optional<String> maybeSpecialCode = Optional.ofNullable(StringUtils.trimToNull(promoCode));
        Optional<SpecialPrice> specialCode = maybeSpecialCode.flatMap(specialPriceRepository::getByCode);
        Optional<PromoCodeDiscount> promotionCodeDiscount = maybeSpecialCode.flatMap((trimmedCode) -> promoCodeDiscountRepository.findPromoCodeInEventOrOrganization(event.getId(), trimmedCode));

        var result = Pair.of(specialCode, promotionCodeDiscount);

        var errorResponse = new ValidatedResponse<>(ValidationResult.failed(new ValidationResult.ErrorDescriptor("promoCode", ErrorsCode.STEP_1_CODE_NOT_FOUND, ErrorsCode.STEP_1_CODE_NOT_FOUND)), result);

        //
        if(specialCode.isPresent()) {
            if (eventManager.getOptionalByIdAndActive(specialCode.get().getTicketCategoryId(), event.getId()).isEmpty()) {
                return errorResponse;
            }

            if (specialCode.get().getStatus() != SpecialPrice.Status.FREE) {
                return errorResponse;
            }

        } else if (promotionCodeDiscount.isPresent() && !promotionCodeDiscount.get().isCurrentlyValid(eventZoneId, now)) {
            return errorResponse;
        } else if (promotionCodeDiscount.isPresent() && isDiscountCodeUsageExceeded(promotionCodeDiscount.get())){
            return errorResponse;
        } else if(promotionCodeDiscount.isEmpty()) {
            return errorResponse;
        }
        //


        return new ValidatedResponse<>(ValidationResult.success(), result);
    }

    private boolean isDiscountCodeUsageExceeded(PromoCodeDiscount discount) {
        return discount.getMaxUsage() != null && discount.getMaxUsage() <= promoCodeDiscountRepository.countConfirmedPromoCode(discount.getId(), categoriesOrNull(discount), null, categoriesOrNull(discount) != null ? "X" : null);
    }


    private static HttpHeaders getCorsHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Access-Control-Allow-Origin", "*");
        return headers;
    }

    private boolean isCaptchaInvalid(String recaptchaResponse, HttpServletRequest request, EventAndOrganizationId event) {
        return configurationManager.isRecaptchaForTicketSelectionEnabled(event)
            && !recaptchaService.checkRecaptcha(recaptchaResponse, request);
    }
}