package dev.belegagent.core.accounting;

/**
 * Expense categories with their SKR03 / SKR04 expense accounts.
 * Numbers from the SKR03 2020 edition and a secondary SKR04 source (see docs/PLAN.md);
 * <b>verify against the current DATEV charts before production use.</b>
 */
public enum ExpenseCategory {
    OFFICE_SUPPLIES("Bürobedarf", "4930", "6815"),
    POSTAGE("Porto", "4910", "6800"),
    TELEPHONE("Telefon", "4920", "6805"),
    INTERNET("Internetkosten", "4925", "6810"),
    ENTERTAINMENT("Bewirtungskosten", "4650", "6640"),
    LITERATURE("Fachliteratur", "4940", "6820"),
    TRAINING("Fortbildungskosten", "4945", "6821"),
    SOFTWARE("Mietleasing Software", "4964", "6837"),
    BANK_FEES("Nebenkosten des Geldverkehrs", "4970", "6855"),
    VEHICLE("Laufende Kfz-Betriebskosten", "4530", "6530"),
    TRAVEL_FARE("Reisekosten Unternehmer, Fahrtkosten", "4673", "6673"),
    ACCOMMODATION("Reisekosten Unternehmer, Übernachtungsaufwand", "4676", "6680"),
    OTHER("Sonstige betriebliche Aufwendungen", "4900", "6300");

    private final String accountName;
    private final String skr03;
    private final String skr04;

    ExpenseCategory(String accountName, String skr03, String skr04) {
        this.accountName = accountName;
        this.skr03 = skr03;
        this.skr04 = skr04;
    }

    public String accountName() {
        return accountName;
    }

    public String account(ChartOfAccounts chart) {
        return chart == ChartOfAccounts.SKR03 ? skr03 : skr04;
    }
}
