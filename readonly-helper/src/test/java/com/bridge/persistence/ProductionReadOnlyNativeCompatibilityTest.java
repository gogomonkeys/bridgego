package com.bridge.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.bridge.persistence.ProductionReadOnlyWorkbookTest.*;
import static com.bridge.persistence.ProductionReadOnlyFormatAudit.*;
import static com.bridge.persistence.ProductionReadOnlyFormatAuditTest.assertSafe;

/** Synthetic exact-theme/link and display-dimension regressions. No production resources or requests. */
class ProductionReadOnlyNativeCompatibilityTest {
  private static final String ISSUER = "https://accounts.google.com";
  private static final List<String> COLOR_NAMES = List.of("TEXT", "BACKGROUND", "ACCENT1", "ACCENT2", "ACCENT3", "ACCENT4", "ACCENT5", "ACCENT6", "LINK");
  private static final List<String> CARLITO = List.of("", "1 1 1", "0.08235294 0.3764706 0.50980395", "0.9137255 0.44313726 0.19607843", "0.09803922 0.41960785 0.14117648", "0.05882353 0.61960787 0.8352941", "0.627451 0.16862746 0.5764706", "0.30588236 0.654902 0.18039216", "0.27450982 0.47058824 0.5254902");
  private static final List<String> ARIAL = List.of("", "1 1 1", "0.25882354 0.52156866 0.95686275", "0.91764706 0.2627451 0.20784314", "0.9843137 0.7372549 0.015686275", "0.20392157 0.65882355 0.3254902", "1 0.42745098 0.003921569", "0.27450982 0.7411765 0.7764706", "0.06666667 0.33333334 0.8");

  static ObjectNode rgb(String values) {
    ObjectNode color = JSON.createObjectNode();
    if (!values.isEmpty()) {
      String[] parts = values.split(" ");
      for (int i = 0; i < 3; i++) color.put(List.of("red", "green", "blue").get(i), new BigDecimal(parts[i]));
    }
    return color;
  }
  static ObjectNode styleFixture(boolean carlito) {
    ObjectNode root = fixture(), p = (ObjectNode)root.get("properties");
    ObjectNode theme = p.putObject("spreadsheetTheme"); theme.put("primaryFontFamily", carlito ? "Carlito" : "Arial");
    ArrayNode colors = theme.putArray("themeColors"); List<String> palette = carlito ? CARLITO : ARIAL;
    for (int i = 0; i < COLOR_NAMES.size(); i++) colors.addObject().put("colorType", COLOR_NAMES.get(i)).putObject("color").set("rgbColor", rgb(palette.get(i)));
    ObjectNode d = p.putObject("defaultFormat");
    d.set("backgroundColor", rgb("1 1 1")); d.putObject("backgroundColorStyle").set("rgbColor", rgb("1 1 1"));
    d.putObject("padding").put("top", 2).put("bottom", 2).put("left", 3).put("right", 3);
    d.put("verticalAlignment", "BOTTOM").put("wrapStrategy", "OVERFLOW_CELL");
    ObjectNode text = d.putObject("textFormat");
    text.put("fontFamily", carlito ? "Carlito" : "arial,sans,sans-serif").put("fontSize", carlito ? 11 : 10);
    text.put("bold", false).put("italic", false).put("strikethrough", false).put("underline", false);
    text.putObject("foregroundColor"); text.putObject("foregroundColorStyle").putObject("rgbColor");
    for (JsonNode sheet : root.path("sheets")) for (JsonNode row : sheet.path("data").get(0).path("rowData")) for (JsonNode raw : row.path("values")) {
      ObjectNode cell = (ObjectNode)raw, effective = d.deepCopy();
      ((ObjectNode)effective.get("textFormat")).put("fontFamily", carlito ? "Carlito" : "Arial");
      effective.put("horizontalAlignment", cell.path("userEnteredValue").has("numberValue") ? "RIGHT" : "LEFT");
      effective.put("hyperlinkDisplayType", "PLAIN_TEXT"); cell.set("effectiveFormat", effective);
    }
    return root;
  }
  static ObjectNode issuer(ObjectNode root) { return cell(root, "auth_identities", 1, 0); }
  static ObjectNode format(ObjectNode c, boolean entered) { return (ObjectNode)c.get(entered ? "userEnteredFormat" : "effectiveFormat"); }
  static ObjectNode text(ObjectNode c, boolean entered) { return (ObjectNode)format(c, entered).get("textFormat"); }
  static void addIssuer(ObjectNode root, boolean carlito) {
    ObjectNode c = issuer(root); c.put("hyperlink", ISSUER);
    c.putObject("userEnteredFormat").putObject("textFormat").putObject("link").put("uri", ISSUER);
    format(c, false).put("hyperlinkDisplayType", "LINKED"); ObjectNode tf = text(c, false);
    tf.put("underline", true); tf.putObject("link").put("uri", ISSUER);
    tf.set("foregroundColor", rgb((carlito ? CARLITO : ARIAL).get(8)));
    tf.putObject("foregroundColorStyle").set("rgbColor", rgb((carlito ? CARLITO : ARIAL).get(8)));
  }
  static ObjectNode linkedFixture(boolean carlito) { ObjectNode n = styleFixture(carlito); addIssuer(n, carlito); return n; }
  static void rejectBoth(Consumer<ObjectNode> mutate) {
    for (boolean carlito : List.of(true, false)) {
      ObjectNode root = linkedFixture(carlito); mutate.accept(root);
      assertThrows(IllegalStateException.class, () -> inspect(root));
    }
  }
  static Summary audit(ObjectNode n) { return ProductionReadOnlyFormatAudit.inspect(n); }

  @Test void exactCarlitoAndArialIssuerStylesAreAcceptedAndRemainInLayoutFingerprint() throws Exception {
    for (boolean carlito : List.of(true, false)) {
      ObjectNode n = styleFixture(carlito); var plain = inspect(n); addIssuer(n, carlito); byte[] before = bytes(n); var linked = inspect(n);
      assertEquals(plain.normalizedDataHash(), linked.normalizedDataHash()); assertEquals(plain.literalTypesHash(), linked.literalTypesHash());
      assertNotEquals(plain.layoutHash(), linked.layoutHash()); assertNotEquals(plain.nativeSha256(), linked.nativeSha256());
      assertEquals("legacy-eight-native-v2-readonly", linked.profileVersion());
      assertFalse(linked.mutationPermitted()); assertFalse(linked.businessSemanticsVerified()); assertFalse(linked.writerExclusionVerified());
      assertArrayEquals(before, bytes(n));
      Summary summary = audit(n); assertTrue(summary.completeScan()); assertEquals(1, summary.counts().get(Counter.KNOWN_ISSUER_LINK_STYLE_EXACT));
      assertEquals(0, summary.counts().get(Counter.UNRECOGNIZED_LINK)); assertSafe(summary);
      assertThrows(IllegalStateException.class, () -> ProductionReadOnlyWorkbook.verify(before, TARGET, plain.layoutHash()));
    }
  }
  @Test void everyIssuerValueAndUriMustMatchExactly() {
    rejectBoth(n -> string(issuer(n), "https://other.invalid"));
    rejectBoth(n -> issuer(n).putObject("effectiveValue").put("stringValue", "https://other.invalid"));
    rejectBoth(n -> issuer(n).put("formattedValue", "https://other.invalid"));
    rejectBoth(n -> issuer(n).put("hyperlink", "https://other.invalid"));
    for (boolean entered : List.of(true, false)) rejectBoth(n -> ((ObjectNode)text(issuer(n), entered).get("link")).put("uri", "https://other.invalid"));
    rejectBoth(n -> issuer(n).remove("hyperlink")); rejectBoth(n -> issuer(n).remove("effectiveFormat"));
    rejectBoth(n -> issuer(n).remove("userEnteredFormat"));
  }
  @Test void exactLinkIsAllowedOnlyInAuthIdentityDataIssuerColumn() {
    for (String title : TABS) if (!title.equals("auth_identities")) rejectBoth(n -> cell(n, title, 1, 0).setAll(issuer(n).deepCopy()));
    for (int column : List.of(1, 2, 3, 25)) rejectBoth(n -> cell(n, "auth_identities", 1, column).setAll(issuer(n).deepCopy()));
    rejectBoth(n -> cell(n, "auth_identities", 0, 0).setAll(issuer(n).deepCopy()));
    rejectBoth(n -> n.withObject("properties").remove("spreadsheetTheme"));
  }
  @Test void populatedAndEmptyUnknownFieldsAreRejectedAtEveryLinkDepth() throws Exception {
    for (boolean entered : List.of(true, false)) for (String path : List.of("", "/textFormat", "/textFormat/link"))
      for (JsonNode value : List.of(TextNode.valueOf("unknown"), TextNode.valueOf(""), JSON.createObjectNode(), JSON.createArrayNode())) {
        rejectBoth(n -> ((ObjectNode)format(issuer(n), entered).at(path)).set("unknown", value));
        ObjectNode n = linkedFixture(true); ((ObjectNode)format(issuer(n), entered).at(path)).set("unknown", value);
        Summary summary = audit(n); assertFalse(summary.completeScan()); assertEquals(0, summary.counts().get(Counter.KNOWN_ISSUER_LINK_STYLE_EXACT)); assertSafe(summary);
      }
    for (String path : List.of("/backgroundColor", "/backgroundColorStyle", "/backgroundColorStyle/rgbColor", "/padding", "/textFormat/foregroundColor", "/textFormat/foregroundColorStyle", "/textFormat/foregroundColorStyle/rgbColor"))
      rejectBoth(n -> ((ObjectNode)format(issuer(n), false).at(path)).putObject("unknown"));
    rejectBoth(n -> issuer(n).putObject("unknown"));
  }
  @Test void knownButUnrequestedFormatsCannotHideAsEmptyObjects() {
    rejectBoth(n -> format(issuer(n), true).putObject("numberFormat"));
    rejectBoth(n -> text(issuer(n), true).put("bold", false));
    rejectBoth(n -> format(issuer(n), false).putObject("numberFormat"));
    rejectBoth(n -> format(issuer(n), false).putObject("borders"));
    rejectBoth(n -> format(issuer(n), false).putObject("textRotation"));
  }
  @Test void effectiveStyleAndBothColorRepresentationsRemainExact() {
    rejectBoth(n -> format(issuer(n), false).put("horizontalAlignment", "RIGHT"));
    rejectBoth(n -> format(issuer(n), false).put("verticalAlignment", "TOP"));
    rejectBoth(n -> format(issuer(n), false).put("wrapStrategy", "WRAP"));
    rejectBoth(n -> format(issuer(n), false).put("hyperlinkDisplayType", "PLAIN_TEXT"));
    rejectBoth(n -> text(issuer(n), false).put("underline", false));
    rejectBoth(n -> text(issuer(n), false).put("bold", true));
    rejectBoth(n -> text(issuer(n), false).put("fontFamily", "Unknown"));
    rejectBoth(n -> text(issuer(n), false).put("fontSize", 12));
    rejectBoth(n -> text(issuer(n), false).put("fontSize", new BigDecimal("11.0")));
    rejectBoth(n -> text(issuer(n), false).putObject("foregroundColorStyle").put("themeColor", "LINK"));
    rejectBoth(n -> ((ObjectNode)text(issuer(n), false).get("foregroundColor")).put("red", new BigDecimal("0.5")));
    rejectBoth(n -> ((ObjectNode)text(issuer(n), false).path("foregroundColorStyle").get("rgbColor")).put("red", new BigDecimal("0.5")));
    rejectBoth(n -> ((ObjectNode)format(issuer(n), false).get("padding")).put("left", 4));
    rejectBoth(n -> text(issuer(n), false).remove("foregroundColor"));
    rejectBoth(n -> text(issuer(n), false).remove("foregroundColorStyle"));
  }
  @Test void wrongPaletteCannotPassParserOrExactAuditTemplate() throws Exception {
    for (boolean carlito : List.of(true, false)) {
      ObjectNode n = linkedFixture(carlito); ObjectNode tf = text(issuer(n), false);
      tf.set("foregroundColor", rgb((carlito ? ARIAL : CARLITO).get(8)));
      tf.putObject("foregroundColorStyle").set("rgbColor", rgb((carlito ? ARIAL : CARLITO).get(8)));
      var badColor = n; assertThrows(IllegalStateException.class, () -> inspect(badColor)); assertEquals(0, audit(n).counts().get(Counter.KNOWN_ISSUER_LINK_STYLE_EXACT));
      n = linkedFixture(carlito);
      ((ObjectNode)n.path("properties").path("spreadsheetTheme").path("themeColors").get(8).path("color")).set("rgbColor", rgb("0.5 0.5 0.5"));
      var badTheme = n; assertThrows(IllegalStateException.class, () -> inspect(badTheme));
      Summary summary = audit(n); assertEquals(0, summary.counts().get(Counter.KNOWN_ISSUER_LINK_STYLE_EXACT)); assertSafe(summary);
    }
  }
  @Test void richTextAndChipsAreNotPartOfTheIssuerException() {
    rejectBoth(n -> issuer(n).putArray("textFormatRuns").addObject().put("startIndex", 0).putObject("format").putObject("link").put("uri", ISSUER));
    rejectBoth(n -> issuer(n).putArray("chipRuns").addObject());
  }
  @Test void inheritedKnownEmptyBlackRgbMayStillBeOmittedButUnknownEmptyFieldsMayNot() {
    for (boolean carlito : List.of(true, false)) {
      ObjectNode n = linkedFixture(carlito); ObjectNode ordinary = text(cell(n, "accounts", 1, 0), false);
      ordinary.remove("foregroundColor"); ordinary.remove("foregroundColorStyle"); assertDoesNotThrow(() -> inspect(n));
    }
    ObjectNode expected = JSON.createObjectNode(); expected.putObject("textFormat").putObject("foregroundColor");
    expected.withObject("textFormat").putObject("foregroundColorStyle").putObject("rgbColor");
    assertTrue(ProductionReadOnlyWorkbook.sameKnownFormat(JSON.createObjectNode(), expected));
    ObjectNode unknown = JSON.createObjectNode(); unknown.putObject("unknown");
    assertFalse(ProductionReadOnlyWorkbook.sameKnownFormat(unknown, expected));
  }
  static ObjectNode dimension(ObjectNode root, boolean row) { return (ObjectNode)block(root, "accounts").path(row ? "rowMetadata" : "columnMetadata").get(0); }
  @Test void supportedWideColumnsChangeLayoutOnlyAndProduceCompleteAudit() throws Exception {
    ObjectNode n = linkedFixture(true); var baseline = inspect(n); String previous = baseline.layoutHash();
    for (int width : List.of(1000, 1001, 10000)) {
      dimension(n, false).put("pixelSize", width); var actual = inspect(n);
      assertNotEquals(previous, actual.layoutHash()); previous = actual.layoutHash();
      assertEquals(baseline.normalizedDataHash(), actual.normalizedDataHash()); assertEquals(baseline.literalTypesHash(), actual.literalTypesHash());
      Summary summary = audit(n); assertTrue(summary.completeScan()); assertTrue(summary.reasons().values().stream().allMatch(v -> v == 0));
      assertEquals(1, summary.counts().get(Counter.KNOWN_ISSUER_LINK_STYLE_EXACT)); assertSafe(summary);
      assertThrows(IllegalStateException.class, () -> ProductionReadOnlyWorkbook.verify(bytes(n), TARGET, baseline.layoutHash()));
    }
  }
  @Test void invalidColumnDimensionsAndRow1001StillRejectWithFixedAuditReasons() throws Exception {
    List<JsonNode> invalid = List.of(IntNode.valueOf(10001), IntNode.valueOf(0), IntNode.valueOf(-1), DecimalNode.valueOf(new BigDecimal("1001.5")), DecimalNode.valueOf(new BigDecimal("1001.0")), TextNode.valueOf("1001"));
    for (JsonNode size : invalid) {
      ObjectNode n = linkedFixture(true); dimension(n, false).set("pixelSize", size);
      assertThrows(IllegalStateException.class, () -> inspect(n)); Summary summary = audit(n); assertFalse(summary.completeScan());
      assertEquals(1, summary.reasons().values().stream().mapToInt(Integer::intValue).sum()); assertSafe(summary);
    }
    ObjectNode n = linkedFixture(true); dimension(n, true).put("pixelSize", 1000); assertDoesNotThrow(() -> inspect(n));
    dimension(n, true).put("pixelSize", 1001); assertThrows(IllegalStateException.class, () -> inspect(n));
    assertEquals(1, audit(n).reasons().get(Reason.ROW_METADATA_PIXEL_SIZE_ABOVE1000_TO10000));
    dimension(n, true).put("pixelSize", 21); dimension(n, false).put("pixelSize", 10000).putObject("unknown");
    assertThrows(IllegalStateException.class, () -> inspect(n)); assertEquals(1, audit(n).reasons().get(Reason.COLUMN_METADATA_FIELDS_UNKNOWN));
  }
}
