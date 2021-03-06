package eduproxy.web;


import org.apache.commons.io.IOUtils;
import org.junit.Test;
import org.opensaml.ws.message.encoder.MessageEncodingException;
import org.opensaml.xml.io.MarshallingException;
import org.opensaml.xml.security.SecurityException;
import org.opensaml.xml.signature.SignatureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.net.UnknownHostException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebSecurityConfigTest extends AbstractWebSecurityConfigTest {

  @Value("${proxy.acs_location}")
  private String serviceProviderACSLocation;

  @Value("${proxy.entity_id}")
  private String serviceProviderEntityId;

  @Test
  public void testProxy() throws Exception {
    String destination = "http://localhost:" + port + "/saml/idp";

    String url = samlRequestUtils.redirectUrl(entityId, destination,
      acsLocation, Optional.empty(), true);

    //This mimics the AuthRequest from a SP to the eduProxy IDP endpoint
    ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

    //This is the AuthnRequest from the eduProxy to the real IdP
    String saml = decodeSaml(response);

    assertTrue(saml.contains("AssertionConsumerServiceURL=\"http://localhost:8080/saml/SSO\""));
    assertTrue(saml.contains("Destination=\"https://engine.test2.surfconext.nl/authentication/idp/single-sign-on\""));

    String samlResponse = getIdPSAMLResponse(saml);

    MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
    map.add("SAMLResponse", Base64.getEncoder().encodeToString(samlResponse.getBytes()));

    HttpHeaders httpHeaders = buildCookieHeaders(response);

    // now mimic a response from the real IdP with a valid AuthnResponse and the correct cookie header
    HttpEntity<MultiValueMap<String, String>> httpEntity = new HttpEntity<>(map, httpHeaders);
    response = restTemplate.exchange("http://localhost:" + port + "/saml/SSO", HttpMethod.POST, httpEntity, String.class);

    assertAuthResponse(response);

    // now verify that we hit the cached principal
    String secondUrl = samlRequestUtils.redirectUrl(entityId, destination, acsLocation, Optional.empty(), false);

    response = restTemplate.exchange(secondUrl, HttpMethod.GET, new HttpEntity<>(httpHeaders), String.class);

    assertAuthResponse(response);

    //we can now call user to introspect the Principal
    response = restTemplate.exchange("http://localhost:" + port + "/user", HttpMethod.GET, new HttpEntity<>(httpHeaders), String.class);

    assertUserResponse(response);
  }

  @Test
  public void testInvalidSignature() throws UnknownHostException, SecurityException, SignatureException, MarshallingException, MessageEncodingException {
    String url = samlRequestUtils.redirectUrl(serviceProviderEntityId, "http://localhost:" + port + "/saml/idp", serviceProviderACSLocation, Optional.empty(), true);
    String mangledUrl = url.replaceFirst("&Signature[^&]+", "&Signature=bogus");

    doAssertInvalidResponse("Exception during validation of AuthnRequest (Error during signature verification)", mangledUrl);
  }

  @Test
  public void testProxyTestEndpoint() throws Exception {
    ResponseEntity<String> response = restTemplate.getForEntity("http://localhost:" + port + "/test", String.class);

    //This is the AuthnRequest from the eduProxy to the real IdP
    String saml = decodeSaml(response);

    assertTrue(saml.contains("AssertionConsumerServiceURL=\"http://localhost:8080/saml/SSO\""));
    assertTrue(saml.contains("Destination=\"https://engine.test2.surfconext.nl/authentication/idp/single-sign-on\""));

    String samlResponse = getIdPSAMLResponse(saml);

    MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
    map.add("SAMLResponse", Base64.getEncoder().encodeToString(samlResponse.getBytes()));

    HttpHeaders httpHeaders = buildCookieHeaders(response);

    // now mimic a response from the real IdP with a valid AuthnResponse and the correct cookie header
    HttpEntity<MultiValueMap<String, String>> httpEntity = new HttpEntity<>(map, httpHeaders);
    response = restTemplate.exchange("http://localhost:" + port + "/saml/SSO", HttpMethod.POST, httpEntity, String.class);

    assertEquals(302, response.getStatusCode().value());

    String location = response.getHeaders().getFirst("Location");
    assertEquals("http://localhost:"+port+"/test", location);

    response = restTemplate.exchange(location, HttpMethod.GET, new HttpEntity<>(httpHeaders), String.class);

    assertUserResponse(response);
  }

  @Test
  public void testInvalidACS() throws UnknownHostException, SecurityException, SignatureException, MarshallingException, MessageEncodingException {
    assertInvalidResponse(entityId, "http://bogus", "ServiceProvider " + entityId + " has not published ACS ");
  }

  @Test
  public void testInvalidEntityID() throws UnknownHostException, SecurityException, SignatureException, MarshallingException, MessageEncodingException {
    String url = samlRequestUtils.redirectUrl("http://bogus", "http://localhost:" + port + "/", acsLocation, Optional.empty(), false);
    doAssertInvalidResponse("ServiceProvider http://bogus is unknown", url);
  }

  @Test
  public void testNoSAML() throws Exception {
    ResponseEntity<String> response = restTemplate.getForEntity("http://localhost:" + port + "/bogus", String.class);
    assertEquals(403, response.getStatusCode().value());
  }

  private void assertUserResponse(ResponseEntity<String> response) {
    assertEquals(200, response.getStatusCode().value());

    String html = response.getBody();

    assertTrue(html.contains("urn:oasis:names:tc:SAML:1.1:nameid-format:unspecified"));
    assertTrue(html.contains("urn:collab:person:example.com:admin"));
    assertTrue(html.contains("j.doe@example.com"));
  }

  private String getIdPSAMLResponse(String saml) throws IOException {
    Matcher matcher = Pattern.compile("ID=\"(.*?)\"").matcher(saml);
    assertTrue(matcher.find());

    //We need the ID of the original request to mimic the real IdP authnResponse
    String inResponseTo = matcher.group(1);

    ZonedDateTime date = ZonedDateTime.now();
    String now = date.format(DateTimeFormatter.ISO_INSTANT);
    String samlResponse = IOUtils.toString(new ClassPathResource("saml/eb.authnResponse.saml.xml").getInputStream());

    //Make sure the all the validations pass. We don't sign as this is in dev modus not necessary (and cumbersome)
    samlResponse = samlResponse
      .replaceAll("@@IssueInstant@@", now)
      .replaceAll("@@NotBefore@@", now)
      .replaceAll("@@NotOnOrAfter@@", date.plus(5, ChronoUnit.MINUTES).format(DateTimeFormatter.ISO_INSTANT))
      .replaceAll("@@InResponseTo@@", inResponseTo);
    return samlResponse;
  }

}
