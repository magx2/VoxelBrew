package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.persistence.DocumentCodec;
import org.junit.jupiter.api.Test;

class NewDocumentPrintDefaultsTest {
    @Test
    void newDesktopProjectsStartWithSupportsDisabledAndPersistTheChoice() throws Exception {
        var document = DocumentController.newDesktopDocument();
        assertTrue(document.printSetup().supportsDisabled());
        assertEquals(document, DocumentCodec.decode(DocumentCodec.encode(document)));
        assertFalse(document.printSetup().withSupportsDisabled(false).supportsDisabled());
    }
}
