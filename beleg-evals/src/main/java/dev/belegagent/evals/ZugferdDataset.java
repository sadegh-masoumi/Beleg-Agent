package dev.belegagent.evals;

import dev.belegagent.core.Beleg;
import dev.belegagent.extraction.einvoice.EInvoice;
import dev.belegagent.extraction.einvoice.ZugferdReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The "ZUGFeRD trick": a German invoice dataset with exact answers for free.
 * Each PDF's embedded XML is the ground truth; the model only sees the rendered pages.
 *
 * <p>Selection: ZUGFeRD 2.x / XRechnung (CII) PDFs from non-"fail" folders, full-invoice profiles only
 * (MINIMUM and BASIC-WL carry no line items), German sellers, deduplicated by invoice number + seller + total
 * (the corpus contains the same sample in several versions).
 */
final class ZugferdDataset {

    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private ZugferdDataset() {
    }

    static List<EvalCase> load(Path root, int maxDocs) {
        TreeMap<String, Path> pdfs = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                    .forEach(p -> pdfs.put(root.relativize(p).toString().replace('\\', '/'), p));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read dataset " + root, e);
        }

        Stats stats = new Stats();
        List<EvalCase> cases = new ArrayList<>();
        Set<String> seenContent = new HashSet<>();
        Set<String> seenInvoices = new HashSet<>();
        for (var entry : pdfs.entrySet()) {
            if (cases.size() >= maxDocs) {
                break;
            }
            String id = entry.getKey();
            if (id.contains("/fail/") || id.startsWith("ZUGFeRDv1/")) {
                stats.excludedFolder++;
                continue;
            }
            byte[] pdf = read(entry.getValue());
            if (pdf.length > MAX_BYTES) {
                stats.tooLarge++;
                continue;
            }
            String sha = sha256(pdf);
            if (!seenContent.add(sha)) {
                stats.duplicates++;
                continue;
            }
            EInvoice invoice = ZugferdReader.fromPdf(pdf).orElse(null);
            if (invoice == null) {
                stats.noCiiXml++;
                continue;
            }
            if (!invoice.profile().isFullInvoice()) {
                stats.notFullProfile++;
                continue;
            }
            if (!"DE".equals(invoice.sellerCountry())) {
                stats.notGerman++;
                continue;
            }
            Beleg truth = invoice.beleg();
            if (truth.grossTotal() == null || truth.invoiceNumber() == null) {
                stats.incompleteTruth++;
                continue;
            }
            String invoiceKey = truth.invoiceNumber() + "|" + truth.sellerName() + "|" + truth.grossTotal().stripTrailingZeros();
            if (!seenInvoices.add(invoiceKey)) {
                stats.duplicates++;
                continue;
            }
            cases.add(new EvalCase(id, sha, pdf, truth, invoice.profile()));
        }
        System.out.printf("Dataset %s: %d PDFs scanned, %d selected (excluded: %s)%n",
                root, pdfs.size(), cases.size(), stats);
        return cases;
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Stats {
        int excludedFolder;
        int tooLarge;
        int duplicates;
        int noCiiXml;
        int notFullProfile;
        int notGerman;
        int incompleteTruth;

        @Override
        public String toString() {
            return "fail/v1 folders=%d, no CII XML=%d, MINIMUM/BASIC-WL=%d, non-German seller=%d, duplicates=%d, too large=%d, incomplete XML=%d"
                    .formatted(excludedFolder, noCiiXml, notFullProfile, notGerman, duplicates, tooLarge, incompleteTruth);
        }
    }
}
