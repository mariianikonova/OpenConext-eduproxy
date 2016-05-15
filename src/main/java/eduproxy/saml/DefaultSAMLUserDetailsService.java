package eduproxy.saml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.saml.SAMLCredential;
import org.springframework.security.saml.userdetails.SAMLUserDetailsService;

import java.security.Principal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

public class DefaultSAMLUserDetailsService implements SAMLUserDetailsService {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultSAMLUserDetailsService.class);

  @Override
  public Principal loadUserBySAML(SAMLCredential credential) {
    LOG.debug("loadUserBySAML {}", credential);

    /*
     * When we got here we are asked to load a User for the AuthnResponse from the
     * real IdP that we need to send back to the SP who initiated the SAML dance.
     *
     * We elevate the current Principal with the AuthnResponse data.
     *
     * See IdentityProviderAuthnFilter and ProxyAuthenticationSuccessHandler
     */
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication == null || !(authentication.getPrincipal() instanceof SAMLPrincipal)) {
      throw new RuntimeException("Authentication.Principal is not SAMLPrincipal");
    }
    SAMLPrincipal principal = (SAMLPrincipal) authentication.getPrincipal();
    List<SAMLAttribute> attributes = credential.getAttributes().stream().map(attribute ->
      new SAMLAttribute(
        attribute.getName(),
        attribute.getAttributeValues().stream().map(SAMLBuilder::getStringValueFromXMLObject)
          .filter(Optional::isPresent).map(Optional::get).collect(toList()))).collect(toList());
    principal.elevate(credential.getNameID().getValue(), attributes);
    return principal;
  }

}
