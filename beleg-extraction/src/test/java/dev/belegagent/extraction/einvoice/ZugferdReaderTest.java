package dev.belegagent.extraction.einvoice;

import static org.assertj.core.api.Assertions.assertThat;

import dev.belegagent.core.Beleg;
import dev.belegagent.core.DocumentKind;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ZugferdReaderTest {

    private static final String CII = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:CrossIndustryInvoice
                xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100"
                xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100"
                xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter><ram:ID>urn:cen.eu:en16931:2017</ram:ID></ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
              <rsm:ExchangedDocument>
                <ram:ID>471102</ram:ID>
                <ram:TypeCode>380</ram:TypeCode>
                <ram:IssueDateTime><udt:DateTimeString format="102">20180305</udt:DateTimeString></ram:IssueDateTime>
              </rsm:ExchangedDocument>
              <rsm:SupplyChainTradeTransaction>
                <ram:IncludedSupplyChainTradeLineItem>
                  <ram:SpecifiedTradeProduct><ram:Name>Trennblätter A4</ram:Name></ram:SpecifiedTradeProduct>
                  <ram:SpecifiedLineTradeAgreement><ram:NetPriceProductTradePrice><ram:ChargeAmount>9.90</ram:ChargeAmount></ram:NetPriceProductTradePrice></ram:SpecifiedLineTradeAgreement>
                  <ram:SpecifiedLineTradeDelivery><ram:BilledQuantity unitCode="H87">20.0000</ram:BilledQuantity></ram:SpecifiedLineTradeDelivery>
                  <ram:SpecifiedLineTradeSettlement>
                    <ram:ApplicableTradeTax><ram:RateApplicablePercent>19.00</ram:RateApplicablePercent></ram:ApplicableTradeTax>
                    <ram:SpecifiedTradeSettlementLineMonetarySummation><ram:LineTotalAmount>198.00</ram:LineTotalAmount></ram:SpecifiedTradeSettlementLineMonetarySummation>
                  </ram:SpecifiedLineTradeSettlement>
                </ram:IncludedSupplyChainTradeLineItem>
                <ram:ApplicableHeaderTradeAgreement>
                  <ram:SellerTradeParty>
                    <ram:Name>Lieferant GmbH</ram:Name>
                    <ram:PostalTradeAddress>
                      <ram:PostcodeCode>80333</ram:PostcodeCode><ram:LineOne>Lieferantenstraße 20</ram:LineOne>
                      <ram:CityName>München</ram:CityName><ram:CountryID>DE</ram:CountryID>
                    </ram:PostalTradeAddress>
                    <ram:SpecifiedTaxRegistration><ram:ID schemeID="FC">201/113/40209</ram:ID></ram:SpecifiedTaxRegistration>
                    <ram:SpecifiedTaxRegistration><ram:ID schemeID="VA">DE123456789</ram:ID></ram:SpecifiedTaxRegistration>
                  </ram:SellerTradeParty>
                  <ram:BuyerTradeParty><ram:Name>Kunden AG Mitte</ram:Name></ram:BuyerTradeParty>
                </ram:ApplicableHeaderTradeAgreement>
                <ram:ApplicableHeaderTradeSettlement>
                  <ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>
                  <ram:ApplicableTradeTax>
                    <ram:CalculatedAmount>37.62</ram:CalculatedAmount><ram:BasisAmount>198.00</ram:BasisAmount>
                    <ram:RateApplicablePercent>19.00</ram:RateApplicablePercent>
                  </ram:ApplicableTradeTax>
                  <ram:SpecifiedTradeSettlementHeaderMonetarySummation>
                    <ram:TaxBasisTotalAmount>198.00</ram:TaxBasisTotalAmount>
                    <ram:TaxTotalAmount currencyID="EUR">37.62</ram:TaxTotalAmount>
                    <ram:GrandTotalAmount>235.62</ram:GrandTotalAmount>
                  </ram:SpecifiedTradeSettlementHeaderMonetarySummation>
                </ram:ApplicableHeaderTradeSettlement>
              </rsm:SupplyChainTradeTransaction>
            </rsm:CrossIndustryInvoice>
            """.strip();

    @Test
    void readsHeaderTotalsLinesAndTaxes() {
        EInvoice invoice = ZugferdReader.fromXml(CII).orElseThrow();
        Beleg b = invoice.beleg();

        assertThat(invoice.profile()).isEqualTo(EInvoiceProfile.EN16931);
        assertThat(invoice.sellerCountry()).isEqualTo("DE");
        assertThat(b.kind()).isEqualTo(DocumentKind.INVOICE);
        assertThat(b.invoiceNumber()).isEqualTo("471102");
        assertThat(b.issueDate()).isEqualTo(LocalDate.of(2018, 3, 5));
        assertThat(b.sellerName()).isEqualTo("Lieferant GmbH");
        assertThat(b.sellerAddress()).isEqualTo("Lieferantenstraße 20, 80333 München");
        assertThat(b.sellerVatId()).isEqualTo("DE123456789");
        assertThat(b.sellerTaxNumber()).isEqualTo("201/113/40209");
        assertThat(b.buyerName()).isEqualTo("Kunden AG Mitte");
        assertThat(b.currency()).isEqualTo("EUR");
        assertThat(b.netTotal()).isEqualByComparingTo("198.00");
        assertThat(b.taxTotal()).isEqualByComparingTo("37.62");
        assertThat(b.grossTotal()).isEqualByComparingTo("235.62");
        assertThat(b.positions()).singleElement().satisfies(p -> {
            assertThat(p.description()).isEqualTo("Trennblätter A4");
            assertThat(p.quantity()).isEqualByComparingTo("20");
            assertThat(p.lineTotal()).isEqualByComparingTo("198.00");
            assertThat(p.taxRatePercent()).isEqualByComparingTo("19");
        });
        assertThat(b.taxLines()).singleElement().satisfies(t ->
                assertThat(t.gross()).isEqualByComparingTo("235.62"));
    }

    @Test
    void creditNoteTypeCode() {
        Beleg b = ZugferdReader.fromXml(CII.replace("<ram:TypeCode>380", "<ram:TypeCode>381")).orElseThrow().beleg();
        assertThat(b.kind()).isEqualTo(DocumentKind.CREDIT_NOTE);
    }

    @Test
    void profilesWithoutLineItemsAreNotFullInvoices() {
        assertThat(EInvoiceProfile.fromGuideline("urn:factur-x.eu:1p0:minimum").isFullInvoice()).isFalse();
        assertThat(EInvoiceProfile.fromGuideline("urn:factur-x.eu:1p0:basicwl").isFullInvoice()).isFalse();
        assertThat(EInvoiceProfile.fromGuideline("urn:cen.eu:en16931:2017#compliant#urn:factur-x.eu:1p0:basic"))
                .isEqualTo(EInvoiceProfile.BASIC);
        assertThat(EInvoiceProfile.fromGuideline(
                "urn:cen.eu:en16931:2017#compliant#urn:xoev-de:kosit:standard:xrechnung_2.3"))
                .isEqualTo(EInvoiceProfile.XRECHNUNG);
    }

    @Test
    void rejectsNonCiiAndXxe() {
        assertThat(ZugferdReader.fromXml("<Invoice xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:Invoice-2\"/>"))
                .isEmpty();
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]>
                <rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100">&e;</rsm:CrossIndustryInvoice>
                """.strip();
        assertThat(ZugferdReader.fromXml(xxe)).isEmpty();
        assertThat(ZugferdReader.fromXml("not xml")).isEmpty();
    }
}
