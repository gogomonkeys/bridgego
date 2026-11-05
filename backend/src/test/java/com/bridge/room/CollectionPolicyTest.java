package com.bridge.room;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CollectionPolicyTest {
  ObjectMapper json=new ObjectMapper();
  String valid="[{\"issuer\":\"https://accounts.google.com\",\"subject\":\"12345\"}]";
  @Test void failClosedByDefaultAndNoPublicSwitch() {
    assertFalse(CollectionPolicy.parse(json,"restricted","","contact","retention").ready());
    assertThrows(IllegalStateException.class,()->CollectionPolicy.parse(json,"public",valid,"contact","retention"));
    assertFalse(CollectionPolicy.parse(json,"restricted",valid,"","retention").ready());
    assertFalse(CollectionPolicy.parse(json,"restricted",valid,"contact","").ready());
  }
  @Test void matchesExactPairOnlyAndNeverEmails() {
    var p=CollectionPolicy.parse(json,"restricted",valid,"contact","retention");assertTrue(p.ready());
    assertTrue(p.admits("https://accounts.google.com","12345"));
    assertFalse(p.admits("accounts.google.com","12345"));assertFalse(p.admits("https://evil.test","12345"));
    assertFalse(p.admits("https://accounts.google.com","123456"));assertFalse(p.admits(null,"12345"));
  }
  @Test void rejectsMalformedWildcardEmailAndUnknownFieldsWithoutLeakingValues() {
    for(String input:Set.of("{}","[{}]",valid.replace("12345","*"),valid.replace("12345","private@example.com"),
        valid.replace("https://accounts.google.com","accounts.google.com"),valid.replace("\"subject\"","\"email\""),"not-json")) {
      var failure=assertThrows(IllegalStateException.class,()->CollectionPolicy.parse(json,"restricted",input,"contact","retention"));
      assertFalse(failure.getMessage().contains("private@example.com"));assertNull(failure.getCause());
    }
  }
  @Test void operationalCollectionRequiresSeparateVerifiedErasureWorkflow() {
    var p = CollectionPolicy.parse(json,"restricted",valid,"contact","retention");
    assertFalse(p.requireVerifiedErasureWorkflow(false).ready());
    assertTrue(p.requireVerifiedErasureWorkflow(true).ready());
    assertFalse(p.requireVerifiedQuarterPolicy(false).ready());
  }
}
