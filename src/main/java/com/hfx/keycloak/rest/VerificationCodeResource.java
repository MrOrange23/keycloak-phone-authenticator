package com.hfx.keycloak.rest;

import com.hfx.keycloak.SmsException;
import com.hfx.keycloak.spi.CaptchaService;
import com.hfx.keycloak.spi.SmsService;
import java.util.regex.Pattern;
import javax.ws.rs.core.Response.Status;
import org.apache.commons.lang.StringUtils;
import org.jboss.logging.Logger;
import org.jboss.resteasy.annotations.cache.NoCache;
import com.hfx.keycloak.VerificationCodeRepresentation;
import com.hfx.keycloak.spi.VerificationCodeService;
import org.jboss.resteasy.specimpl.MultivaluedMapImpl;
import org.jboss.resteasy.spi.HttpRequest;
import org.jboss.resteasy.spi.UnauthorizedException;
import org.keycloak.common.ClientConnection;
import org.keycloak.jose.jws.JWSInput;
import org.keycloak.jose.jws.JWSInputException;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.resources.admin.AdminAuth;
import org.keycloak.services.resources.admin.permissions.AdminPermissionEvaluator;
import org.keycloak.services.resources.admin.permissions.AdminPermissions;

import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VerificationCodeResource {

    private static final Logger log = Logger.getLogger(VerificationCodeResource.class);

    public static final String CAPTCHA_KEY = "com.hfx.CAPTCHA_KEY";
    public static final String CAPTCHA_SECRET = "com.hfx.CAPTCHA_SECRET";

    private static final Pattern E164_STANDARD_REGEX = Pattern.compile("^\\+?[1-9]\\d{1,14}$");

    private final KeycloakSession session;

    @Context
    private HttpHeaders httpHeaders;

    @Context
    private ClientConnection clientConnection;

    private AppAuthManager authManager;
    protected AdminPermissionEvaluator auth;

    public VerificationCodeResource(KeycloakSession session) {
        this.httpHeaders = session.getContext().getRequestHeaders();
        this.clientConnection = session.getContext().getConnection();
        this.authManager = new AppAuthManager();
        this.session = session;
    }

    @GET
    @Path("")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public List<VerificationCodeRepresentation> getVerificationCodes() {
        evaluator().users().requireManage();
        return session.getProvider(VerificationCodeService.class).listVerificationCodes();
    }

    @POST
    @Path("")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response createVerificationCode(@Context HttpRequest request, final Map<String, String> formData0) {
        String captchaKey = session.getContext().getRealm().getAttribute(CAPTCHA_KEY);
        String captchaSecret = session.getContext().getRealm().getAttribute(CAPTCHA_SECRET);

        MultivaluedMap<String, String> formData = new MultivaluedMapImpl<>();
        formData0.forEach(formData::putSingle);

        if (StringUtils.isNotEmpty(captchaKey) && StringUtils.isNotEmpty(captchaSecret)) {
            if (!session.getProvider(CaptchaService.class).verify(captchaKey, captchaSecret, formData)) {
                throw new UnauthorizedException("Captcha validation is required");
            }
        }

        // Check that provided phone number is valid
        String phoneNumber = formData.getFirst("phoneNumber");
        if (!isValidPhoneNumber(phoneNumber)) {
            throw new BadRequestException("Provided phone number is invalid");
        }

        // check that user with provided phone number exist
        List<UserModel> users = session.users().searchForUserByUserAttribute("phoneNumber",
            phoneNumber, session.getContext().getRealm());

        if (users == null || users.isEmpty()) {
            throw new NotFoundException("Not found user for given phoneNumber " + phoneNumber);
        }

        VerificationCodeRepresentation rep = new VerificationCodeRepresentation();
        rep.setPhoneNumber(formData.getFirst("phoneNumber"));
        rep.setKind(formData.getFirst("kind"));
        VerificationCodeRepresentation vc = session.getProvider(VerificationCodeService.class).addVerificationCode(rep);
        Map<String, Object> params = new HashMap<>();
        try {
            session.getProvider(SmsService.class).sendVerificationCode(vc, params);
        }
        catch (SmsException e) {
            log.error(e.getMessage());
        }
        return Response.noContent().build();
    }

    protected AdminAuth authenticateRealmAdminRequest() {
        String tokenString = authManager.extractAuthorizationHeaderToken(httpHeaders);
        if (tokenString == null) throw new UnauthorizedException("Bearer");
        AccessToken token;
        try {
            JWSInput input = new JWSInput(tokenString);
            token = input.readJsonContent(AccessToken.class);
        } catch (JWSInputException e) {
            throw new UnauthorizedException("Bearer token format error");
        }
        String realmName = token.getIssuer().substring(token.getIssuer().lastIndexOf('/') + 1);
        RealmManager realmManager = new RealmManager(session);
        RealmModel realm = realmManager.getRealmByName(realmName);
        if (realm == null) {
            throw new UnauthorizedException("Unknown realm in token");
        }
        session.getContext().setRealm(realm);
        AuthenticationManager.AuthResult authResult = new AppAuthManager.BearerTokenAuthenticator(session)
                .setRealm(realm)
                .setConnection(clientConnection)
                .setHeaders(this.httpHeaders)
                .authenticate();

        if (authResult == null) {
            log.debug("Token not valid");
            throw new UnauthorizedException("Bearer");
        }

        ClientModel client = realm.getClientByClientId(token.getIssuedFor());
        if (client == null) {
            throw new NotFoundException("Could not find client for authorization");
        }

        return new AdminAuth(realm, authResult.getToken(), authResult.getUser(), client);
    }

    private AdminPermissionEvaluator evaluator() {
        if (this.auth == null) {
            this.auth = AdminPermissions.evaluator(session, session.getContext().getRealm(), authenticateRealmAdminRequest());
        }

        return auth;
    }

    /**
     * Check {@code phoneNumber} is valid according to E.164 standart
     * @param phoneNumber phone number
     *
     * @see <a href="https://en.wikipedia.org/wiki/E.164">https://en.wikipedia.org/wiki/E.164</a>
     */
    private static boolean isValidPhoneNumber(String phoneNumber) {
        return E164_STANDARD_REGEX.matcher(phoneNumber).matches();
    }

}
