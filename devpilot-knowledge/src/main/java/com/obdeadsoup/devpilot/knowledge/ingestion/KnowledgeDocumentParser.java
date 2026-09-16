package com.obdeadsoup.devpilot.knowledge.ingestion;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.knowledge.error.KnowledgeErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

@Component
public final class KnowledgeDocumentParser {
    private static final Set<String> TEXT_EXTENSIONS = Set.of(".md", ".txt");
    private static final int MAX_EXTRACTED_CHARACTERS = 2_000_000;

    public String parse(String filename, byte[] content) {
        String extension = extension(filename);
        try {
            String text = TEXT_EXTENSIONS.contains(extension) ? decodeUtf8(content) : parsePdf(extension, content);
            String normalized = text.replace("\u0000", "").replace("\r\n", "\n").strip();
            if (normalized.isBlank() || normalized.length() > MAX_EXTRACTED_CHARACTERS) {
                throw new BusinessException(KnowledgeErrorCode.INVALID_DOCUMENT);
            }
            return normalized;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(KnowledgeErrorCode.INVALID_DOCUMENT);
        }
    }

    public boolean supports(String filename) {
        String extension = extension(filename);
        return TEXT_EXTENSIONS.contains(extension) || ".pdf".equals(extension);
    }

    private String parsePdf(String extension, byte[] content) throws Exception {
        if (!".pdf".equals(extension)) {
            throw new BusinessException(KnowledgeErrorCode.INVALID_DOCUMENT);
        }
        try (PDDocument document = Loader.loadPDF(content)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String decodeUtf8(byte[] content) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content)).toString();
    }

    private String extension(String filename) {
        String normalized = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = normalized.lastIndexOf('.');
        return dot < 0 ? "" : normalized.substring(dot);
    }
}
