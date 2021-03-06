/*
 * Copyright 2010-2013 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.jaxrs;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.ws.rs.core.Response.Status;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;

import com.ning.billing.GuicyKillbillTestSuiteWithEmbeddedDB;
import com.ning.billing.catalog.api.BillingPeriod;
import com.ning.billing.catalog.api.Currency;
import com.ning.billing.catalog.api.PriceListSet;
import com.ning.billing.catalog.api.ProductCategory;
import com.ning.billing.jaxrs.json.AccountEmailJson;
import com.ning.billing.jaxrs.json.AccountJson;
import com.ning.billing.jaxrs.json.AccountTimelineJson;
import com.ning.billing.jaxrs.json.CatalogJsonSimple;
import com.ning.billing.jaxrs.json.ChargebackJson;
import com.ning.billing.jaxrs.json.CreditJson;
import com.ning.billing.jaxrs.json.InvoiceItemJson;
import com.ning.billing.jaxrs.json.InvoiceJson;
import com.ning.billing.jaxrs.json.OverdueStateJson;
import com.ning.billing.jaxrs.json.PaymentJson;
import com.ning.billing.jaxrs.json.PaymentMethodJson;
import com.ning.billing.jaxrs.json.PaymentMethodJson.PaymentMethodPluginDetailJson;
import com.ning.billing.jaxrs.json.PaymentMethodJson.PaymentMethodProperties;
import com.ning.billing.jaxrs.json.PlanDetailJson;
import com.ning.billing.jaxrs.json.RefundJson;
import com.ning.billing.jaxrs.json.SubscriptionJson;
import com.ning.billing.jaxrs.json.TenantJson;
import com.ning.billing.jaxrs.resources.JaxrsResource;
import com.ning.billing.util.api.AuditLevel;
import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClient.BoundRequestBuilder;
import com.ning.http.client.ListenableFuture;
import com.ning.http.client.Realm;
import com.ning.http.client.Realm.AuthScheme;
import com.ning.http.client.Response;
import com.ning.jetty.core.CoreConfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableMap;

import static com.ning.billing.jaxrs.resources.JaxrsResource.HDR_API_KEY;
import static com.ning.billing.jaxrs.resources.JaxrsResource.HDR_API_SECRET;
import static com.ning.billing.jaxrs.resources.JaxrsResource.OVERDUE;
import static com.ning.billing.jaxrs.resources.JaxrsResource.QUERY_DELETE_DEFAULT_PM_WITH_AUTO_PAY_OFF;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public abstract class KillbillClient extends GuicyKillbillTestSuiteWithEmbeddedDB {

    protected static final String PLUGIN_NAME = "noop";

    protected static final int DEFAULT_HTTP_TIMEOUT_SEC = 20;

    protected static final Map<String, String> DEFAULT_EMPTY_QUERY = new HashMap<String, String>();

    private static final Logger log = LoggerFactory.getLogger(TestJaxrsBase.class);

    public static final String HEADER_CONTENT_TYPE = "Content-type";
    public static final String CONTENT_TYPE = "application/json";

    protected static final String DEFAULT_CURRENCY = "USD";

    protected CoreConfig config;
    protected AsyncHttpClient httpClient;
    protected ObjectMapper mapper;

    // Multi-Tenancy information, if enabled
    protected String DEFAULT_API_KEY = UUID.randomUUID().toString();
    protected String DEFAULT_API_SECRET = UUID.randomUUID().toString();
    protected String apiKey = DEFAULT_API_KEY;
    protected String apiSecret = DEFAULT_API_SECRET;

    // RBAC information, if enabled
    protected String username = null;
    protected String password = null;

    // Context information to be passed around
    protected static final String createdBy = "Toto";
    protected static final String reason = "i am god";
    protected static final String comment = "no comment";

    protected List<PaymentMethodProperties> getPaymentMethodCCProperties() {
        final List<PaymentMethodProperties> properties = new ArrayList<PaymentMethodProperties>();
        properties.add(new PaymentMethodProperties("type", "CreditCard", false));
        properties.add(new PaymentMethodProperties("cardType", "Visa", false));
        properties.add(new PaymentMethodProperties("cardHolderName", "Mr Sniff", false));
        properties.add(new PaymentMethodProperties("expirationDate", "2015-08", false));
        properties.add(new PaymentMethodProperties("maskNumber", "3451", false));
        properties.add(new PaymentMethodProperties("address1", "23, rue des cerisiers", false));
        properties.add(new PaymentMethodProperties("address2", "", false));
        properties.add(new PaymentMethodProperties("city", "Toulouse", false));
        properties.add(new PaymentMethodProperties("country", "France", false));
        properties.add(new PaymentMethodProperties("postalCode", "31320", false));
        properties.add(new PaymentMethodProperties("state", "Midi-Pyrenees", false));
        return properties;
    }

    protected List<PaymentMethodProperties> getPaymentMethodPaypalProperties() {
        final List<PaymentMethodProperties> properties = new ArrayList<PaymentMethodJson.PaymentMethodProperties>();
        properties.add(new PaymentMethodProperties("type", "CreditCard", false));
        properties.add(new PaymentMethodProperties("email", "zouzou@laposte.fr", false));
        properties.add(new PaymentMethodProperties("baid", "23-8787d-R", false));
        return properties;
    }

    protected PaymentMethodJson getPaymentMethodJson(final String accountId, final List<PaymentMethodProperties> properties) {
        final PaymentMethodPluginDetailJson info = new PaymentMethodPluginDetailJson(null, null, null, null, null, null, null, null, null, null, null, null, null, null, properties);
        return new PaymentMethodJson(null, accountId, true, PLUGIN_NAME, info);
    }

    //
    // TENANT UTILITIES
    //

    protected String createTenant(final String apiKey, final String apiSecret) throws Exception {
        final String baseJson = mapper.writeValueAsString(new TenantJson(null, null, apiKey, apiSecret));
        final Response response = doPost(JaxrsResource.TENANTS_PATH, baseJson, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());
        return response.getHeader("Location");
    }

    protected String registerCallbackNotificationForTenant(final String callback) throws Exception {
        final Map<String, String> queryParams = new HashMap<String, String>();
        queryParams.put(JaxrsResource.QUERY_NOTIFICATION_CALLBACK, callback);
        final String uri = JaxrsResource.TENANTS_PATH + "/" + JaxrsResource.REGISTER_NOTIFICATION_CALLBACK;
        final Response response = doPost(uri, null, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());
        return response.getHeader("Location");
    }

    //
    // SECURITY UTILITIES
    //

    protected void loginAsAdmin() {
        loginAs("tester", "tester");
    }

    protected void loginAs(final String username, final String password) {
        this.username = username;
        this.password = password;
    }

    protected void logout() {
        this.username = null;
        this.password = null;
    }

    protected List<String> getPermissions(@Nullable final String username, @Nullable final String password) throws Exception {
        final String oldUsername = this.username;
        final String oldPassword = this.password;

        this.username = username;
        this.password = password;

        final Response response = doGet(JaxrsResource.SECURITY_PATH + "/permissions", DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        this.username = oldUsername;
        this.password = oldPassword;

        final String baseJson = response.getResponseBody();
        final List<String> objFromJson = mapper.readValue(baseJson, new TypeReference<List<String>>() {});
        Assert.assertNotNull(objFromJson);

        return objFromJson;
    }

    //
    // ACCOUNT UTILITIES
    //

    protected AccountJson getAccountById(final String id) throws Exception {
        final String uri = JaxrsResource.ACCOUNTS_PATH + "/" + id;
        final Response response = doGet(uri, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        final String baseJson = response.getResponseBody();
        final AccountJson objFromJson = mapper.readValue(baseJson, AccountJson.class);
        Assert.assertNotNull(objFromJson);

        return objFromJson;
    }

    protected AccountJson getAccountByExternalKey(final String externalKey) throws Exception {
        final Response response = getAccountByExternalKeyNoValidation(externalKey);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        final String baseJson = response.getResponseBody();
        final AccountJson objFromJson = mapper.readValue(baseJson, AccountJson.class);
        Assert.assertNotNull(objFromJson);

        return objFromJson;
    }

    protected List<AccountJson> searchAccountsByKey(final String key) throws Exception {
        final String uri = JaxrsResource.ACCOUNTS_PATH + "/" + JaxrsResource.SEARCH + "/" + key;
        final Response response = doGet(uri, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        final String baseJson = response.getResponseBody();
        final List<AccountJson> objFromJson = mapper.readValue(baseJson, new TypeReference<List<AccountJson>>() {});
        Assert.assertNotNull(objFromJson);

        return objFromJson;
    }

    protected Response getAccountByExternalKeyNoValidation(final String externalKey) {
        final Map<String, String> queryParams = new HashMap<String, String>();
        queryParams.put(JaxrsResource.QUERY_EXTERNAL_KEY, externalKey);
        return doGet(JaxrsResource.ACCOUNTS_PATH, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
    }

    protected AccountTimelineJson getAccountTimeline(final String accountId) throws Exception {
        return doGetAccountTimeline(accountId, AuditLevel.NONE);
    }

    protected AccountTimelineJson getAccountTimelineWithAudits(final String accountId, final AuditLevel auditLevel) throws Exception {
        return doGetAccountTimeline(accountId, auditLevel);
    }

    private AccountTimelineJson doGetAccountTimeline(final String accountId, final AuditLevel auditLevel) throws Exception {
        final String uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountId + "/" + JaxrsResource.TIMELINE;

        final Response response = doGet(uri, ImmutableMap.<String, String>of(JaxrsResource.QUERY_AUDIT, auditLevel.toString()), DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        final String baseJson = response.getResponseBody();
        final AccountTimelineJson objFromJson = mapper.readValue(baseJson, AccountTimelineJson.class);
        assertNotNull(objFromJson);

        return objFromJson;
    }

    protected AccountJson createAccountWithDefaultPaymentMethod() throws Exception {
        final AccountJson input = createAccount();
        return doCreateAccountWithDefaultPaymentMethod(input);
    }

    protected AccountJson createAccountWithDefaultPaymentMethod(final String name, final String key, final String email) throws Exception {
        final AccountJson input = createAccount(name, key, email);
        return doCreateAccountWithDefaultPaymentMethod(input);
    }

    protected AccountJson doCreateAccountWithDefaultPaymentMethod(final AccountJson input) throws Exception {
        final String uri = JaxrsResource.ACCOUNTS_PATH + "/" + input.getAccountId() + "/" + JaxrsResource.PAYMENT_METHODS;
        final PaymentMethodJson paymentMethodJson = getPaymentMethodJson(input.getAccountId(), null);
        String baseJson = mapper.writeValueAsString(paymentMethodJson);
        Map<String, String> queryParams = new HashMap<String, String>();
        queryParams.put(JaxrsResource.QUERY_PAYMENT_METHOD_IS_DEFAULT, "true");

        Response response = doPost(uri, baseJson, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());

        queryParams = new HashMap<String, String>();
        queryParams.put(JaxrsResource.QUERY_EXTERNAL_KEY, input.getExternalKey());
        response = doGet(JaxrsResource.ACCOUNTS_PATH, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        baseJson = response.getResponseBody();
        final AccountJson objFromJson = mapper.readValue(baseJson, AccountJson.class);
        Assert.assertNotNull(objFromJson);
        Assert.assertNotNull(objFromJson.getPaymentMethodId());
        return objFromJson;
    }

    protected AccountJson createAccount() throws Exception {
        return createAccount(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString().substring(0, 5) + '@' + UUID.randomUUID().toString().substring(0, 5));
    }

    protected AccountJson createAccount(final String name, final String key, final String email) throws Exception {
        Response response = createAccountNoValidation(name, key, email);
        final String baseJson;
        Assert.assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());

        final String location = response.getHeader("Location");
        Assert.assertNotNull(location);

        // Retrieves by Id based on Location returned
        response = doGetWithUrl(location, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        baseJson = response.getResponseBody();
        final AccountJson objFromJson = mapper.readValue(baseJson, AccountJson.class);
        Assert.assertNotNull(objFromJson);
        return objFromJson;
    }

    protected Response createAccountNoValidation() throws IOException {
        return createAccountNoValidation(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString().substring(0, 5) + '@' + UUID.randomUUID().toString().substring(0, 5));
    }

    protected Response createAccountNoValidation(final String name, final String key, final String email) throws IOException {
        final AccountJson input = getAccountJson(name, key, email);
        final String baseJson = mapper.writeValueAsString(input);
        return doPost(JaxrsResource.ACCOUNTS_PATH, baseJson, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
    }

    protected AccountJson updateAccount(final String accountId, final AccountJson newInput) throws Exception {
        final String baseJson = mapper.writeValueAsString(newInput);

        final String uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountId;
        final Response response = doPut(uri, baseJson, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        final String retrievedJson = response.getResponseBody();
        final AccountJson objFromJson = mapper.readValue(retrievedJson, AccountJson.class);
        assertNotNull(objFromJson);

        return objFromJson;
    }

    protected List<AccountEmailJson> getEmailsForAccount(final String accountId) throws Exception {
        final String uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountId + "/" + JaxrsResource.EMAILS;

        final Response response = doGet(uri, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        return mapper.readValue(response.getResponseBody(), new TypeReference<List<AccountEmailJson>>() {});
    }

    protected void addEmailToAccount(final String accountId, final AccountEmailJson email) throws Exception {
        final String uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountId + "/" + JaxrsResource.EMAILS;

        final String emailString = mapper.writeValueAsString(email);
        final Response response = doPost(uri, emailString, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());
    }

    protected void removeEmailFromAccount(final String accountId, final String email) throws Exception {
        final String uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountId + "/" + JaxrsResource.EMAILS;

        final Response fifthResponse = doDelete(uri + "/" + email, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(fifthResponse.getStatusCode(), javax.ws.rs.core.Response.Status.OK.getStatusCode());
    }



    protected SubscriptionJson createEntitlement(final String accountId, final String bundleExternalKey, final String productName, final String productCategory, final String billingPeriod, final boolean waitCompletion) throws Exception {

        final SubscriptionJson input =  new SubscriptionJson(accountId, null, null, bundleExternalKey, null ,productName, productCategory,
                                                                            billingPeriod, PriceListSet.DEFAULT_PRICELIST_NAME, null, null, null, null, null,
                                                                            null, null, null);
        String baseJson = mapper.writeValueAsString(input);

        final Map<String, String> queryParams = waitCompletion ? getQueryParamsForCallCompletion("5") : DEFAULT_EMPTY_QUERY;
        Response response = doPost(JaxrsResource.SUBSCRIPTIONS_PATH, baseJson, queryParams, DEFAULT_HTTP_TIMEOUT_SEC * 1000);
        Assert.assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());

        final String location = response.getHeader("Location");
        Assert.assertNotNull(location);

        // Retrieves by Id based on Location returned

        response = doGetWithUrl(location, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        baseJson = response.getResponseBody();
        final SubscriptionJson objFromJson = mapper.readValue(baseJson, SubscriptionJson.class);
        return objFromJson;
    }

    //
    // INVOICE UTILITIES
    //

    protected AccountJson createAccountWithPMBundleAndSubscriptionAndWaitForFirstInvoice() throws Exception {
        final AccountJson accountJson = createAccountWithDefaultPaymentMethod();
        assertNotNull(accountJson);

        // Add a bundle, subscription and move the clock to get the first invoice
        final SubscriptionJson subscriptionJson = createEntitlement(accountJson.getAccountId(), UUID.randomUUID().toString(), "Shotgun", ProductCategory.BASE.toString(), BillingPeriod.MONTHLY.toString(), true);
        assertNotNull(subscriptionJson);
        clock.addDays(32);
        crappyWaitForLackOfProperSynchonization();

        return accountJson;
    }

    protected AccountJson createAccountNoPMBundleAndSubscriptionAndWaitForFirstInvoice() throws Exception {
        // Create an account with no payment method
        final AccountJson accountJson = createAccount();
        assertNotNull(accountJson);

        // Add a bundle, subscription and move the clock to get the first invoice
        final SubscriptionJson subscriptionJson = createEntitlement(accountJson.getAccountId(), UUID.randomUUID().toString(), "Shotgun", ProductCategory.BASE.toString(), BillingPeriod.MONTHLY.toString(), true);
        assertNotNull(subscriptionJson);
        clock.addMonths(1);
        crappyWaitForLackOfProperSynchonization();

        // No payment will be triggered as the account doesn't have a payment method

        return accountJson;
    }

    protected InvoiceJson getInvoice(final String invoiceId) throws IOException {
        return getInvoiceWithAudits(invoiceId, AuditLevel.NONE);
    }

    protected InvoiceJson getInvoiceWithAudits(final String invoiceId, final AuditLevel auditLevel) throws IOException {
        return doGetInvoice(invoiceId, Boolean.FALSE, InvoiceJson.class, auditLevel);
    }

    protected InvoiceJson getInvoice(final Integer invoiceNumber) throws IOException {
        return getInvoice(invoiceNumber.toString());
    }

    protected InvoiceJson getInvoiceWithItems(final String invoiceId) throws IOException {
        return getInvoiceWithItemsWithAudits(invoiceId, AuditLevel.NONE);
    }

    protected InvoiceJson getInvoiceWithItemsWithAudits(final String invoiceId, final AuditLevel auditLevel) throws IOException {
        return doGetInvoice(invoiceId, Boolean.TRUE, InvoiceJson.class, auditLevel);
    }

    private <T> T doGetInvoice(final String invoiceId, final Boolean withItems, final Class<T> clazz, final AuditLevel auditLevel) throws IOException {
        final String uri = JaxrsResource.INVOICES_PATH + "/" + invoiceId;

        final Map<String, String> queryParams = new HashMap<String, String>();
        queryParams.put(JaxrsResource.QUERY_INVOICE_WITH_ITEMS, withItems.toString());
        queryParams.put(JaxrsResource.QUERY_AUDIT, auditLevel.toString());

        final Response response = doGet(uri, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        final String baseJson = response.getResponseBody();

        final T firstInvoiceJson = mapper.readValue(baseJson, clazz);
        assertNotNull(firstInvoiceJson);

        return firstInvoiceJson;
    }

    protected List<InvoiceJson> getInvoicesForAccount(final String accountId) throws IOException {
        return getInvoicesForAccountWithAudits(accountId, AuditLevel.NONE);
    }

    protected List<InvoiceJson> getInvoicesForAccountWithAudits(final String accountId, final AuditLevel auditLevel) throws IOException {
        return doGetInvoicesForAccount(accountId, Boolean.FALSE, new TypeReference<List<InvoiceJson>>() {}, auditLevel);
    }

    protected List<InvoiceJson> getInvoicesWithItemsForAccount(final String accountId) throws IOException {
        return getInvoicesWithItemsForAccountWithAudits(accountId, AuditLevel.NONE);
    }

    protected List<InvoiceJson> getInvoicesWithItemsForAccountWithAudits(final String accountId, final AuditLevel auditLevel) throws IOException {
        return doGetInvoicesForAccount(accountId, Boolean.TRUE, new TypeReference<List<InvoiceJson>>() {}, auditLevel);
    }

    private <T> List<T> doGetInvoicesForAccount(final String accountId, final Boolean withItems, final TypeReference<List<T>> clazz, final AuditLevel auditLevel) throws IOException {

        final String invoicesURI = JaxrsResource.ACCOUNTS_PATH + "/" + accountId + "/" + JaxrsResource.INVOICES;

        final Map<String, String> queryParams = new HashMap<String, String>();
        queryParams.put(JaxrsResource.QUERY_INVOICE_WITH_ITEMS, withItems.toString());
        queryParams.put(JaxrsResource.QUERY_AUDIT, auditLevel.toString());

        final Response invoicesResponse = doGet(invoicesURI, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(invoicesResponse.getStatusCode(), Status.OK.getStatusCode());

        final String invoicesBaseJson = invoicesResponse.getResponseBody();
        final List<T> invoices = mapper.readValue(invoicesBaseJson, clazz);
        assertNotNull(invoices);

        return invoices;
    }

    protected InvoiceJson createDryRunInvoice(final String accountId, final DateTime futureDate) throws IOException {
        final String uri = JaxrsResource.INVOICES_PATH;

        final Map<String, String> queryParams = new HashMap<String, String>();
        queryParams.put(JaxrsResource.QUERY_ACCOUNT_ID, accountId);
        queryParams.put(JaxrsResource.QUERY_TARGET_DATE, futureDate.toString());
        queryParams.put(JaxrsResource.QUERY_DRY_RUN, "true");

        final Response response = doPost(uri, null, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        final String baseJson = response.getResponseBody();
        final InvoiceJson futureInvoice = mapper.readValue(baseJson, InvoiceJson.class);
        assertNotNull(futureInvoice);

        return futureInvoice;
    }

    protected void createInvoice(final String accountId, final DateTime futureDate) throws IOException {
        final String uri = JaxrsResource.INVOICES_PATH;

        final Map<String, String> queryParams = new HashMap<String, String>();
        queryParams.put(JaxrsResource.QUERY_ACCOUNT_ID, accountId);
        queryParams.put(JaxrsResource.QUERY_TARGET_DATE, futureDate.toString());

        final Response response = doPost(uri, null, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());

        final String location = response.getHeader("Location");
        Assert.assertNotNull(location);
    }

    protected void adjustInvoiceItem(final String accountId, final String invoiceId, final String invoiceItemId,
                                     @Nullable final DateTime requestedDate, @Nullable final BigDecimal amount, @Nullable final Currency currency) throws IOException {
        final String uri = JaxrsResource.INVOICES_PATH + "/" + invoiceId;

        final Map<String, String> queryParams = new HashMap<String, String>();
        if (requestedDate != null) {
            queryParams.put(JaxrsResource.QUERY_REQUESTED_DT, requestedDate.toDateTimeISO().toString());
        }

        final InvoiceItemJson adjustment = new InvoiceItemJson(invoiceItemId, null, null, accountId, null, null, null, null,
                                                                           null, null, null, null, amount, currency, null);
        final String adjustmentJson = mapper.writeValueAsString(adjustment);
        final Response response = doPost(uri, adjustmentJson, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());
    }

    protected InvoiceJson createExternalCharge(final String accountId, final BigDecimal amount, @Nullable final String bundleId,
                                                        @Nullable final Currency currency, @Nullable final DateTime requestedDate, final Boolean autoPay) throws Exception {
        return doCreateExternalCharge(accountId, null, bundleId, amount, currency, requestedDate, autoPay, JaxrsResource.CHARGES_PATH);
    }

    protected InvoiceJson createExternalChargeForInvoice(final String accountId, final String invoiceId, @Nullable final String bundleId, final BigDecimal amount,
                                                                  @Nullable final Currency currency, @Nullable final DateTime requestedDate, final Boolean autoPay) throws Exception {
        final String uri = JaxrsResource.INVOICES_PATH + "/" + invoiceId + "/" + JaxrsResource.CHARGES;
        return doCreateExternalCharge(accountId, invoiceId, bundleId, amount, currency, requestedDate, autoPay, uri);
    }

    private InvoiceJson doCreateExternalCharge(final String accountId, @Nullable final String invoiceId, @Nullable final String bundleId, @Nullable final BigDecimal amount,
                                                        @Nullable final Currency currency, final DateTime requestedDate, final Boolean autoPay, final String uri) throws IOException {
        final Map<String, String> queryParams = new HashMap<String, String>();
        if (requestedDate != null) {
            queryParams.put(JaxrsResource.QUERY_REQUESTED_DT, requestedDate.toDateTimeISO().toString());
        }
        if (autoPay) {
            queryParams.put(JaxrsResource.QUERY_PAY_INVOICE, "true");
        }

        final InvoiceItemJson externalCharge = new InvoiceItemJson(null, invoiceId, null, accountId, bundleId, null, null, null,
                                                                               null, null, null, null, amount, currency, null);
        final String externalChargeJson = mapper.writeValueAsString(externalCharge);
        final Response response = doPost(uri, externalChargeJson, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());

        final String location = response.getHeader("Location");
        Assert.assertNotNull(location);

        final Map<String, String> queryParamsForInvoice = new HashMap<String, String>();
        queryParamsForInvoice.put(JaxrsResource.QUERY_ACCOUNT_ID, accountId);
        queryParamsForInvoice.put(JaxrsResource.QUERY_INVOICE_WITH_ITEMS, "true");
        final Response invoiceResponse = doGetWithUrl(location, queryParamsForInvoice, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(invoiceResponse.getStatusCode(), Status.OK.getStatusCode());

        final String invoicesBaseJson = invoiceResponse.getResponseBody();
        final InvoiceJson invoice = mapper.readValue(invoicesBaseJson, new TypeReference<InvoiceJson>() {});
        assertNotNull(invoice);

        return invoice;
    }

    //
    // PAYMENT UTILITIES
    //

    protected PaymentJson getPayment(final String paymentId) throws IOException {
        return doGetPayment(paymentId, DEFAULT_EMPTY_QUERY, PaymentJson.class);
    }

    protected PaymentJson getPaymentWithRefundsAndChargebacks(final String paymentId) throws IOException {
        return doGetPayment(paymentId, ImmutableMap.<String, String>of(JaxrsResource.QUERY_PAYMENT_WITH_REFUNDS_AND_CHARGEBACKS, "true"), PaymentJson.class);
    }

    protected <T extends PaymentJson> T doGetPayment(final String paymentId, final Map<String, String> queryParams, final Class<T> clazz) throws IOException {
        final String paymentURI = JaxrsResource.PAYMENTS_PATH + "/" + paymentId;

        final Response paymentResponse = doGet(paymentURI, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(paymentResponse.getStatusCode(), Status.OK.getStatusCode());

        final T paymentJsonSimple = mapper.readValue(paymentResponse.getResponseBody(), clazz);
        assertNotNull(paymentJsonSimple);

        return paymentJsonSimple;
    }

    protected PaymentMethodJson getPaymentMethod(final String paymentMethodId) throws IOException {
        final String paymentMethodURI = JaxrsResource.PAYMENT_METHODS_PATH + "/" + paymentMethodId;
        final Response paymentMethodResponse = doGet(paymentMethodURI, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(paymentMethodResponse.getStatusCode(), Status.OK.getStatusCode());

        final PaymentMethodJson paymentMethodJson = mapper.readValue(paymentMethodResponse.getResponseBody(), PaymentMethodJson.class);
        assertNotNull(paymentMethodJson);

        return paymentMethodJson;
    }

    protected PaymentMethodJson getPaymentMethodWithPluginInfo(final String paymentMethodId) throws IOException {
        final String paymentMethodURI = JaxrsResource.PAYMENT_METHODS_PATH + "/" + paymentMethodId;

        final Response paymentMethodResponse = doGet(paymentMethodURI,
                                                     ImmutableMap.<String, String>of(JaxrsResource.QUERY_PAYMENT_METHOD_PLUGIN_INFO, "true"),
                                                     DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(paymentMethodResponse.getStatusCode(), Status.OK.getStatusCode());

        final PaymentMethodJson paymentMethodJson = mapper.readValue(paymentMethodResponse.getResponseBody(), PaymentMethodJson.class);
        assertNotNull(paymentMethodJson);

        return paymentMethodJson;
    }

    protected List<PaymentMethodJson> searchPaymentMethodsByKey(final String key) throws Exception {
        return searchPaymentMethodsByKeyAndPlugin(key, null);
    }

    protected List<PaymentMethodJson> searchPaymentMethodsByKeyAndPlugin(final String key, @Nullable final String pluginName) throws Exception {
        final String uri = JaxrsResource.PAYMENT_METHODS_PATH + "/" + JaxrsResource.SEARCH + "/" + key;
        final Response response = doGet(uri,
                                        pluginName == null ? DEFAULT_EMPTY_QUERY : ImmutableMap.<String, String>of(JaxrsResource.QUERY_PAYMENT_METHOD_PLUGIN_NAME, pluginName),
                                        DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        final String baseJson = response.getResponseBody();
        final List<PaymentMethodJson> objFromJson = mapper.readValue(baseJson, new TypeReference<List<PaymentMethodJson>>() {});
        Assert.assertNotNull(objFromJson);

        return objFromJson;
    }

    protected void deletePaymentMethod(final String paymentMethodId, final Boolean deleteDefault) throws IOException {
        final String paymentMethodURI = JaxrsResource.PAYMENT_METHODS_PATH + "/" + paymentMethodId;

        final Response response = doDelete(paymentMethodURI, ImmutableMap.<String, String>of(QUERY_DELETE_DEFAULT_PM_WITH_AUTO_PAY_OFF, deleteDefault.toString()), DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(response.getStatusCode(), javax.ws.rs.core.Response.Status.OK.getStatusCode());
    }

    protected List<PaymentJson> getPaymentsForAccount(final String accountId) throws IOException {
        final String paymentsURI = JaxrsResource.ACCOUNTS_PATH + "/" + accountId + "/" + JaxrsResource.PAYMENTS;
        final Response paymentsResponse = doGet(paymentsURI, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(paymentsResponse.getStatusCode(), Status.OK.getStatusCode());
        final String paymentsBaseJson = paymentsResponse.getResponseBody();

        final List<PaymentJson> paymentJsons = mapper.readValue(paymentsBaseJson, new TypeReference<List<PaymentJson>>() {});
        assertNotNull(paymentJsons);

        return paymentJsons;
    }

    protected List<PaymentJson> getPaymentsForInvoice(final String invoiceId) throws IOException {
        final String uri = JaxrsResource.INVOICES_PATH + "/" + invoiceId + "/" + JaxrsResource.PAYMENTS;
        final Response response = doGet(uri, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);

        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        final String baseJson = response.getResponseBody();
        final List<PaymentJson> objFromJson = mapper.readValue(baseJson, new TypeReference<List<PaymentJson>>() {});
        assertNotNull(objFromJson);

        return objFromJson;
    }

    protected void payAllInvoices(final AccountJson accountJson, final Boolean externalPayment) throws IOException {
        final PaymentJson payment = new PaymentJson(null, null, accountJson.getAccountId(), null, null, null, null,
                                                                null, null, 0, null, null, null, null, null, null, null, null);
        final String postJson = mapper.writeValueAsString(payment);

        final String uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountJson.getAccountId() + "/" + JaxrsResource.PAYMENTS;
        doPost(uri, postJson, ImmutableMap.<String, String>of("externalPayment", externalPayment.toString()), DEFAULT_HTTP_TIMEOUT_SEC);
    }

    protected List<PaymentJson> createInstaPayment(final AccountJson accountJson, final InvoiceJson invoice) throws IOException {
        final PaymentJson payment = new PaymentJson(invoice.getAmount(), BigDecimal.ZERO, accountJson.getAccountId(),
                                                                invoice.getInvoiceId(), null, null, null, null, null, 0, null, null, null, null, null, null, null, null);
        final String postJson = mapper.writeValueAsString(payment);

        final String uri = JaxrsResource.INVOICES_PATH + "/" + invoice.getInvoiceId() + "/" + JaxrsResource.PAYMENTS;
        doPost(uri, postJson, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);

        return getPaymentsForInvoice(invoice.getInvoiceId());
    }

    protected List<PaymentJson> createExternalPayment(final AccountJson accountJson, final String invoiceId, final BigDecimal paidAmount) throws IOException {
        final PaymentJson payment = new PaymentJson(paidAmount, BigDecimal.ZERO, accountJson.getAccountId(),
                                                                invoiceId, null, null, null, null, null, 0,
                                                                null, null, null, null, null, null, null, null);
        final String postJson = mapper.writeValueAsString(payment);

        final String paymentURI = JaxrsResource.INVOICES_PATH + "/" + invoiceId + "/" + JaxrsResource.PAYMENTS;
        final Response paymentResponse = doPost(paymentURI, postJson, ImmutableMap.<String, String>of("externalPayment", "true"), DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(paymentResponse.getStatusCode(), Status.CREATED.getStatusCode());

        return getPaymentsForInvoice(invoiceId);
    }

    //
    // CHARGEBACKS
    //

    protected ChargebackJson createChargeBack(final String paymentId, final BigDecimal chargebackAmount) throws IOException {
        final ChargebackJson input = new ChargebackJson(null, null, null, null, chargebackAmount, paymentId, null, null);
        final String jsonInput = mapper.writeValueAsString(input);

        // Create the chargeback
        final Response response = doPost(JaxrsResource.CHARGEBACKS_PATH, jsonInput, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode(), response.getResponseBody());

        // Find the chargeback by location
        final String location = response.getHeader("Location");
        assertNotNull(location);
        final Response responseByLocation = doGetWithUrl(location, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(responseByLocation.getStatusCode(), Status.OK.getStatusCode());

        return mapper.readValue(responseByLocation.getResponseBody(), ChargebackJson.class);
    }

    //
    // REFUNDS
    //

    protected List<RefundJson> getRefundsForAccount(final String accountId) throws IOException {
        final String uri = JaxrsResource.ACCOUNTS_PATH + "/" + accountId + "/" + JaxrsResource.REFUNDS;
        final Response response = doGet(uri, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);

        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        final String baseJson = response.getResponseBody();
        final List<RefundJson> refunds = mapper.readValue(baseJson, new TypeReference<List<RefundJson>>() {});
        assertNotNull(refunds);

        return refunds;
    }

    protected List<RefundJson> getRefundsForPayment(final String paymentId) throws IOException {
        final String uri = JaxrsResource.PAYMENTS_PATH + "/" + paymentId + "/" + JaxrsResource.REFUNDS;
        final Response response = doGet(uri, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);

        assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        final String baseJson = response.getResponseBody();
        final List<RefundJson> refunds = mapper.readValue(baseJson, new TypeReference<List<RefundJson>>() {});
        assertNotNull(refunds);

        return refunds;
    }

    protected RefundJson createRefund(final String paymentId, final BigDecimal amount) throws IOException {
        return doCreateRefund(paymentId, amount, false, ImmutableMap.<String, BigDecimal>of());
    }

    protected RefundJson createRefundWithInvoiceAdjustment(final String paymentId, final BigDecimal amount) throws IOException {
        return doCreateRefund(paymentId, amount, true, ImmutableMap.<String, BigDecimal>of());
    }

    protected RefundJson createRefundWithInvoiceItemAdjustment(final String paymentId, final String invoiceItemId, final BigDecimal amount) throws IOException {
        final Map<String, BigDecimal> adjustments = new HashMap<String, BigDecimal>();
        adjustments.put(invoiceItemId, amount);
        return doCreateRefund(paymentId, amount, true, adjustments);
    }

    private RefundJson doCreateRefund(final String paymentId, final BigDecimal amount, final boolean adjusted, final Map<String, BigDecimal> itemAdjustments) throws IOException {
        final String uri = JaxrsResource.PAYMENTS_PATH + "/" + paymentId + "/" + JaxrsResource.REFUNDS;

        final List<InvoiceItemJson> adjustments = new ArrayList<InvoiceItemJson>();
        for (final String itemId : itemAdjustments.keySet()) {
            adjustments.add(new InvoiceItemJson(itemId, null, null, null, null, null, null, null, null, null, null, null,
                                                      itemAdjustments.get(itemId), null, null));
        }
        final RefundJson refundJson = new RefundJson(null, paymentId, amount, DEFAULT_CURRENCY, adjusted, null, null, adjustments, null);
        final String baseJson = mapper.writeValueAsString(refundJson);
        final Response response = doPost(uri, baseJson, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode());

        final String locationCC = response.getHeader("Location");
        Assert.assertNotNull(locationCC);

        // Retrieves by Id based on Location returned
        final Response retrievedResponse = doGetWithUrl(locationCC, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(retrievedResponse.getStatusCode(), Status.OK.getStatusCode());
        final String retrievedBaseJson = retrievedResponse.getResponseBody();
        final RefundJson retrievedRefundJson = mapper.readValue(retrievedBaseJson, RefundJson.class);
        assertNotNull(retrievedRefundJson);
        // Verify we have the adjusted items
        if (retrievedRefundJson.getAdjustments() != null) {
            final Set<String> allLinkedItemIds = new HashSet<String>(Collections2.transform(retrievedRefundJson.getAdjustments(), new Function<InvoiceItemJson, String>() {
                @Override
                public String apply(@Nullable final InvoiceItemJson input) {
                    if (input != null) {
                        return input.getLinkedInvoiceItemId();
                    } else {
                        return null;
                    }
                }
            }));
            assertEquals(allLinkedItemIds, itemAdjustments.keySet());
        }

        return retrievedRefundJson;
    }

    protected Map<String, String> getQueryParamsForCallCompletion(final String timeoutSec) {
        final Map<String, String> queryParams = new HashMap<String, String>();
        queryParams.put(JaxrsResource.QUERY_CALL_COMPLETION, "true");
        queryParams.put(JaxrsResource.QUERY_CALL_TIMEOUT, timeoutSec);
        return queryParams;
    }

    //
    // CREDITS
    //

    protected CreditJson createCreditForAccount(final String accountId, final BigDecimal creditAmount,
                                                final DateTime requestedDate, final DateTime effectiveDate) throws IOException {
        return createCreditForInvoice(accountId, null, creditAmount, requestedDate, effectiveDate);
    }

    protected CreditJson createCreditForInvoice(final String accountId, final String invoiceId, final BigDecimal creditAmount,
                                                final DateTime requestedDate, final DateTime effectiveDate) throws IOException {
        final CreditJson input = new CreditJson(creditAmount, invoiceId, UUID.randomUUID().toString(),
                                                effectiveDate.toLocalDate(),
                                                accountId,
                                                null);
        final String jsonInput = mapper.writeValueAsString(input);

        // Create the credit
        Response response = doPost(JaxrsResource.CREDITS_PATH, jsonInput, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(response.getStatusCode(), Status.CREATED.getStatusCode(), response.getResponseBody());

        final String location = response.getHeader("Location");
        assertNotNull(location);

        // Retrieves by Id based on Location returned
        response = doGetWithUrl(location, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(response.getStatusCode(), Status.OK.getStatusCode());

        return mapper.readValue(response.getResponseBody(), CreditJson.class);
    }

    //
    // OVERDUE
    //


    protected OverdueStateJson getOverdueStateForAccount(final String accountId) throws Exception {
        final String overdueURI = JaxrsResource.ACCOUNTS_PATH + "/" + accountId + "/" + OVERDUE;
        final Response overdueResponse = doGet(overdueURI, DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        assertEquals(overdueResponse.getStatusCode(), Status.OK.getStatusCode());

        final OverdueStateJson overdueStateJson = mapper.readValue(overdueResponse.getResponseBody(), OverdueStateJson.class);
        assertNotNull(overdueStateJson);

        return overdueStateJson;
    }

    //
    // PLUGINS
    //

    protected Response pluginGET(final String uri) throws Exception {
        return pluginGET(uri, DEFAULT_EMPTY_QUERY);
    }

    protected Response pluginGET(final String uri, final Map<String, String> queryParams) throws Exception {
        return doGet(JaxrsResource.PLUGINS_PATH + "/" + uri, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
    }

    protected Response pluginHEAD(final String uri) throws Exception {
        return pluginHEAD(uri, DEFAULT_EMPTY_QUERY);
    }

    protected Response pluginHEAD(final String uri, final Map<String, String> queryParams) throws Exception {
        return doHead(JaxrsResource.PLUGINS_PATH + "/" + uri, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
    }

    protected Response pluginPOST(final String uri, @Nullable final String body) throws Exception {
        return pluginPOST(uri, body, DEFAULT_EMPTY_QUERY);
    }

    protected Response pluginPOST(final String uri, @Nullable final String body, final Map<String, String> queryParams) throws Exception {
        return doPost(JaxrsResource.PLUGINS_PATH + "/" + uri, body, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
    }

    protected Response pluginPUT(final String uri, @Nullable final String body) throws Exception {
        return pluginPUT(uri, body, DEFAULT_EMPTY_QUERY);
    }

    protected Response pluginPUT(final String uri, @Nullable final String body, final Map<String, String> queryParams) throws Exception {
        return doPut(JaxrsResource.PLUGINS_PATH + "/" + uri, body, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
    }

    protected Response pluginDELETE(final String uri) throws Exception {
        return pluginDELETE(uri, DEFAULT_EMPTY_QUERY);
    }

    protected Response pluginDELETE(final String uri, final Map<String, String> queryParams) throws Exception {
        return doDelete(JaxrsResource.PLUGINS_PATH + "/" + uri, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
    }

    protected Response pluginOPTIONS(final String uri) throws Exception {
        return pluginOPTIONS(uri, DEFAULT_EMPTY_QUERY);
    }

    protected Response pluginOPTIONS(final String uri, final Map<String, String> queryParams) throws Exception {
        return doOptions(JaxrsResource.PLUGINS_PATH + "/" + uri, queryParams, DEFAULT_HTTP_TIMEOUT_SEC);
    }

    //
    // CATALOG
    //

    public CatalogJsonSimple getSimpleCatalog() throws Exception {
        final Response response = doGet(JaxrsResource.CATALOG_PATH + "/simpleCatalog", DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        final String body = response.getResponseBody();
        return mapper.readValue(body, CatalogJsonSimple.class);
    }

    public List<PlanDetailJson> getAvailableAddons(final String baseProductName) throws Exception {
        final Response response = doGet(JaxrsResource.CATALOG_PATH + "/availableAddons",
                                        ImmutableMap.<String, String>of("baseProductName", baseProductName),
                                        DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        final String body = response.getResponseBody();
        return mapper.readValue(body, new TypeReference<List<PlanDetailJson>>() {});
    }

    public List<PlanDetailJson> getBasePlans() throws Exception {
        final Response response = doGet(JaxrsResource.CATALOG_PATH + "/availableBasePlans", DEFAULT_EMPTY_QUERY, DEFAULT_HTTP_TIMEOUT_SEC);
        Assert.assertEquals(response.getStatusCode(), Status.OK.getStatusCode());
        final String body = response.getResponseBody();
        return mapper.readValue(body, new TypeReference<List<PlanDetailJson>>() {});
    }

    //
    // HTTP CLIENT HELPERS
    //
    protected Response doPost(final String uri, @Nullable final String body, final Map<String, String> queryParams, final int timeoutSec) {
        final BoundRequestBuilder builder = getBuilderWithHeaderAndQuery("POST", getUrlFromUri(uri), queryParams);
        if (body != null) {
            builder.setBody(body);
        } else {
            builder.setBody("{}");
        }
        return executeAndWait(builder, timeoutSec, true);
    }

    protected Response doPut(final String uri, final String body, final Map<String, String> queryParams, final int timeoutSec) {
        final String url = String.format("http://%s:%d%s", config.getServerHost(), config.getServerPort(), uri);
        final BoundRequestBuilder builder = getBuilderWithHeaderAndQuery("PUT", url, queryParams);
        if (body != null) {
            builder.setBody(body);
        } else {
            builder.setBody("{}");
        }
        return executeAndWait(builder, timeoutSec, true);
    }

    protected Response doDelete(final String uri, final Map<String, String> queryParams, final int timeoutSec) {
        final String url = String.format("http://%s:%d%s", config.getServerHost(), config.getServerPort(), uri);
        final BoundRequestBuilder builder = getBuilderWithHeaderAndQuery("DELETE", url, queryParams);
        return executeAndWait(builder, timeoutSec, true);
    }

    protected Response doGet(final String uri, final Map<String, String> queryParams, final int timeoutSec) {
        final String url = String.format("http://%s:%d%s", config.getServerHost(), config.getServerPort(), uri);
        return doGetWithUrl(url, queryParams, timeoutSec);
    }

    protected Response doHead(final String uri, final Map<String, String> queryParams, final int timeoutSec) {
        final String url = String.format("http://%s:%d%s", config.getServerHost(), config.getServerPort(), uri);
        return doHeadWithUrl(url, queryParams, timeoutSec);
    }

    protected Response doOptions(final String uri, final Map<String, String> queryParams, final int timeoutSec) {
        final String url = String.format("http://%s:%d%s", config.getServerHost(), config.getServerPort(), uri);
        return doOptionsWithUrl(url, queryParams, timeoutSec);
    }

    protected Response doGetWithUrl(final String url, final Map<String, String> queryParams, final int timeoutSec) {
        final BoundRequestBuilder builder = getBuilderWithHeaderAndQuery("GET", url, queryParams);
        return executeAndWait(builder, timeoutSec, false);
    }

    protected Response doHeadWithUrl(final String url, final Map<String, String> queryParams, final int timeoutSec) {
        final BoundRequestBuilder builder = getBuilderWithHeaderAndQuery("HEAD", url, queryParams);
        return executeAndWait(builder, timeoutSec, false);
    }

    protected Response doOptionsWithUrl(final String url, final Map<String, String> queryParams, final int timeoutSec) {
        final BoundRequestBuilder builder = getBuilderWithHeaderAndQuery("OPTIONS", url, queryParams);
        return executeAndWait(builder, timeoutSec, false);
    }

    protected Response executeAndWait(final BoundRequestBuilder builder, final int timeoutSec, final boolean addContextHeader) {

        if (addContextHeader) {
            builder.addHeader(JaxrsResource.HDR_CREATED_BY, createdBy);
            builder.addHeader(JaxrsResource.HDR_REASON, reason);
            builder.addHeader(JaxrsResource.HDR_COMMENT, comment);
        }

        if (username != null && password != null) {
            final Realm realm = new Realm.RealmBuilder()
                    .setPrincipal(username)
                    .setPassword(password)
                    .setUsePreemptiveAuth(true)
                    .setScheme(AuthScheme.BASIC)
                    .build();
            builder.setRealm(realm);
        }

        Response response = null;
        try {
            final ListenableFuture<Response> futureStatus =
                    builder.execute(new AsyncCompletionHandler<Response>() {
                        @Override
                        public Response onCompleted(final Response response) throws Exception {
                            return response;
                        }
                    });
            response = futureStatus.get(timeoutSec, TimeUnit.SECONDS);
        } catch (final Exception e) {
            Assert.fail(e.getMessage());
        }
        Assert.assertNotNull(response);
        return response;
    }

    protected String getUrlFromUri(final String uri) {
        return String.format("http://%s:%d%s", config.getServerHost(), config.getServerPort(), uri);
    }

    protected BoundRequestBuilder getBuilderWithHeaderAndQuery(final String verb, final String url, final Map<String, String> queryParams) {
        BoundRequestBuilder builder = null;
        if (verb.equals("GET")) {
            builder = httpClient.prepareGet(url);
        } else if (verb.equals("POST")) {
            builder = httpClient.preparePost(url);
        } else if (verb.equals("PUT")) {
            builder = httpClient.preparePut(url);
        } else if (verb.equals("DELETE")) {
            builder = httpClient.prepareDelete(url);
        } else if (verb.equals("HEAD")) {
            builder = httpClient.prepareHead(url);
        } else if (verb.equals("OPTIONS")) {
            builder = httpClient.prepareOptions(url);
        } else {
            Assert.fail("Unknown verb " + verb);
        }

        builder.addHeader(HEADER_CONTENT_TYPE, CONTENT_TYPE);
        builder.addHeader(HDR_API_KEY, apiKey);
        builder.addHeader(HDR_API_SECRET, apiSecret);
        for (final Entry<String, String> q : queryParams.entrySet()) {
            builder.addQueryParameter(q.getKey(), q.getValue());
        }

        return builder;
    }

    protected AccountJson getAccountJson() {
        return getAccountJson(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString().substring(0, 5) + '@' + UUID.randomUUID().toString().substring(0, 5));
    }

    public AccountJson getAccountJson(final String name, final String externalKey, final String email) {
        final String accountId = UUID.randomUUID().toString();
        final int length = 4;
        final String currency = DEFAULT_CURRENCY;
        final String timeZone = "UTC";
        final String address1 = "12 rue des ecoles";
        final String address2 = "Poitier";
        final String postalCode = "44 567";
        final String company = "Renault";
        final String city = "Quelque part";
        final String state = "Poitou";
        final String country = "France";
        final String locale = "fr";
        final String phone = "81 53 26 56";

        // Note: the accountId payload is ignored on account creation
        return new AccountJson(accountId, name, length, externalKey, email, null, currency, null, timeZone,
                               address1, address2, postalCode, company, city, state, country, locale, phone, false, false, null, null);
    }

    /**
     * We could implement a ClockResource in jaxrs with the ability to sync on user token
     * but until we have a strong need for it, this is in the TODO list...
     */
    protected void crappyWaitForLackOfProperSynchonization() throws Exception {
        Thread.sleep(5000);
    }
}
