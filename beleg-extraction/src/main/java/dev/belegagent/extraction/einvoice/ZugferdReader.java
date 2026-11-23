package dev.belegagent.extraction.einvoice;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.DocumentKind;
import dev.belegagent.core.Position;
import dev.belegagent.core.TaxLine;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.mustangproject.ZUGFeRD.ZUGFeRDInvoiceImporter;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Reads ZUGFeRD 2.x / Factur-X / XRechnung (CII syntax) invoices deterministically, without an LLM.
 * Mustang extracts the embedded XML from the PDF; the CII fields are read with XPath so the values
 * are exactly those stated in the XML (no recalculation).
 *
 * <p>Used for the "no LLM" route (Phase 3) and as ground truth for evals: the model sees only the
 * rendered page, its answer is compared with the XML of the same PDF.
 */
public final class ZugferdReader {

    private static final String CII_NS = "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100";
    private static final Map<String, String> NAMESPACES = Map.of(
            "rsm", CII_NS,
            "ram", "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100",
            "udt", "urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100");
    private static final DateTimeFormatter FORMAT_102 = DateTimeFormatter.BASIC_ISO_DATE;

    private static final String SETTLEMENT = "/rsm:CrossIndustryInvoice/rsm:SupplyChainTradeTransaction/ram:ApplicableHeaderTradeSettlement";
    private static final String AGREEMENT = "/rsm:CrossIndustryInvoice/rsm:SupplyChainTradeTransaction/ram:ApplicableHeaderTradeAgreement";
    private static final String SELLER = AGREEMENT + "/ram:SellerTradeParty";
    private static final String TOTALS = SETTLEMENT + "/ram:SpecifiedTradeSettlementHeaderMonetarySummation";

    private ZugferdReader() {
    }

    /** Extracts and parses the embedded XML; empty if the PDF carries no CII invoice. */
    public static Optional<EInvoice> fromPdf(byte[] pdf) {
        String xml;
        try {
            ZUGFeRDInvoiceImporter importer = new ZUGFeRDInvoiceImporter(new ByteArrayInputStream(pdf));
            xml = importer.getUTF8();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return xml == null || xml.isBlank() ? Optional.empty() : fromXml(xml);
    }

    /** Parses CII XML; empty if it is not a CII invoice (e.g. ZUGFeRD 1.0 or UBL). */
    public static Optional<EInvoice> fromXml(String xml) {
        Document doc = parseSecurely(xml);
        if (doc == null || !CII_NS.equals(doc.getDocumentElement().getNamespaceURI())) {
            return Optional.empty();
        }
        try {
            return Optional.of(read(doc));
        } catch (XPathExpressionException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static EInvoice read(Document doc) throws XPathExpressionException {
        XPath x = xpath();
        String currency = text(x, doc, SETTLEMENT + "/ram:InvoiceCurrencyCode");

        List<Position> positions = new ArrayList<>();
        NodeList items = (NodeList) x.evaluate(
                "/rsm:CrossIndustryInvoice/rsm:SupplyChainTradeTransaction/ram:IncludedSupplyChainTradeLineItem",
                doc, XPathConstants.NODESET);
        for (int i = 0; i < items.getLength(); i++) {
            Node item = items.item(i);
            positions.add(new Position(
                    text(x, item, "ram:SpecifiedTradeProduct/ram:Name"),
                    amount(x, item, "ram:SpecifiedLineTradeDelivery/ram:BilledQuantity"),
                    amount(x, item, "ram:SpecifiedLineTradeAgreement/ram:NetPriceProductTradePrice/ram:ChargeAmount"),
                    amount(x, item, "ram:SpecifiedLineTradeSettlement/ram:SpecifiedTradeSettlementLineMonetarySummation/ram:LineTotalAmount"),
                    amount(x, item, "ram:SpecifiedLineTradeSettlement/ram:ApplicableTradeTax/ram:RateApplicablePercent")));
        }

        List<TaxLine> taxLines = new ArrayList<>();
        NodeList taxes = (NodeList) x.evaluate(SETTLEMENT + "/ram:ApplicableTradeTax", doc, XPathConstants.NODESET);
        for (int i = 0; i < taxes.getLength(); i++) {
            Node tax = taxes.item(i);
            BigDecimal basis = amount(x, tax, "ram:BasisAmount");
            BigDecimal calculated = amount(x, tax, "ram:CalculatedAmount");
            taxLines.add(new TaxLine(amount(x, tax, "ram:RateApplicablePercent"), basis, calculated,
                    basis != null && calculated != null ? basis.add(calculated) : null));
        }

        // TaxTotalAmount may appear once per currency; take the one in invoice currency.
        BigDecimal taxTotal = currency == null ? null
                : amount(x, doc, TOTALS + "/ram:TaxTotalAmount[@currencyID='" + currency + "']");
        if (taxTotal == null) {
            taxTotal = amount(x, doc, TOTALS + "/ram:TaxTotalAmount");
        }

        String typeCode = text(x, doc, "/rsm:CrossIndustryInvoice/rsm:ExchangedDocument/ram:TypeCode");
        Beleg beleg = new Beleg(
                "381".equals(typeCode) ? DocumentKind.CREDIT_NOTE : DocumentKind.INVOICE,
                text(x, doc, SELLER + "/ram:Name"),
                address(x, doc),
                text(x, doc, SELLER + "/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='VA']"),
                text(x, doc, SELLER + "/ram:SpecifiedTaxRegistration/ram:ID[@schemeID='FC']"),
                text(x, doc, AGREEMENT + "/ram:BuyerTradeParty/ram:Name"),
                text(x, doc, "/rsm:CrossIndustryInvoice/rsm:ExchangedDocument/ram:ID"),
                date(text(x, doc, "/rsm:CrossIndustryInvoice/rsm:ExchangedDocument/ram:IssueDateTime/udt:DateTimeString")),
                date(text(x, doc, "/rsm:CrossIndustryInvoice/rsm:SupplyChainTradeTransaction/ram:ApplicableHeaderTradeDelivery"
                        + "/ram:ActualDeliverySupplyChainEvent/ram:OccurrenceDateTime/udt:DateTimeString")),
                currency,
                positions,
                taxLines,
                amount(x, doc, TOTALS + "/ram:TaxBasisTotalAmount"),
                taxTotal,
                amount(x, doc, TOTALS + "/ram:GrandTotalAmount"),
                amount(x, doc, TOTALS + "/ram:DuePayableAmount"),
                null);

        EInvoiceProfile profile = EInvoiceProfile.fromGuideline(text(x, doc,
                "/rsm:CrossIndustryInvoice/rsm:ExchangedDocumentContext/ram:GuidelineSpecifiedDocumentContextParameter/ram:ID"));
        return new EInvoice(beleg, profile, text(x, doc, SELLER + "/ram:PostalTradeAddress/ram:CountryID"));
    }

    private static String address(XPath x, Document doc) throws XPathExpressionException {
        String base = SELLER + "/ram:PostalTradeAddress/ram:";
        StringJoiner joiner = new StringJoiner(", ");
        String line = text(x, doc, base + "LineOne");
        String postcode = text(x, doc, base + "PostcodeCode");
        String city = text(x, doc, base + "CityName");
        if (line != null) {
            joiner.add(line);
        }
        String place = ((postcode == null ? "" : postcode) + " " + (city == null ? "" : city)).strip();
        if (!place.isEmpty()) {
            joiner.add(place);
        }
        return joiner.length() == 0 ? null : joiner.toString();
    }

    private static String text(XPath x, Object context, String expression) throws XPathExpressionException {
        Node node = (Node) x.evaluate(expression, context, XPathConstants.NODE);
        if (node == null) {
            return null;
        }
        String value = node.getTextContent().strip();
        return value.isEmpty() ? null : value;
    }

    private static BigDecimal amount(XPath x, Object context, String expression) throws XPathExpressionException {
        String value = text(x, context, expression);
        try {
            return value == null ? null : new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate date(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value, FORMAT_102);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Documents are untrusted: no DTDs, no external entities (XXE). */
    private static Document parseSecurely(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            return null;
        }
    }

    private static XPath xpath() {
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new NamespaceContext() {
            @Override
            public String getNamespaceURI(String prefix) {
                return NAMESPACES.getOrDefault(prefix, XMLConstants.NULL_NS_URI);
            }

            @Override
            public String getPrefix(String namespaceURI) {
                return null;
            }

            @Override
            public Iterator<String> getPrefixes(String namespaceURI) {
                return List.<String>of().iterator();
            }
        });
        return xpath;
    }
}
