package eduproxy.saml;

import eduproxy.AbstractIntegrationTest;
import org.junit.Test;
import org.opensaml.saml2.metadata.EntitiesDescriptor;
import org.opensaml.saml2.metadata.EntityDescriptor;
import org.opensaml.saml2.metadata.provider.MetadataProvider;
import org.opensaml.saml2.metadata.provider.MetadataProviderException;
import org.opensaml.xml.ConfigurationException;
import org.opensaml.xml.parse.XMLParserException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;

import static org.junit.Assert.assertFalse;

public class ResourceMetadataProviderTest extends AbstractIntegrationTest {

  @Autowired
  @Qualifier("metadata")
  private MetadataProvider metadataProvider;

  @Test
  public void test() throws MetadataProviderException, XMLParserException, ConfigurationException {
    EntitiesDescriptor entitiesDescriptor = (EntitiesDescriptor) metadataProvider.getMetadata();
    List<EntityDescriptor> entityDescriptors = entitiesDescriptor.getEntityDescriptors();
    assertFalse( entityDescriptors.isEmpty());
  }

}
