package indi.somebottle;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CommandLineTest {
    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void listShowsDeclaredAndUnlistedIncrementalsWithoutCreatingOutput() throws Exception {
        File group = createBackupGroup();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            assertEquals(0, CommandLine.runCommandLine(new String[]{"--group-dir", group.getAbsolutePath(), "--list"}));
        } finally {
            System.setOut(originalOut);
        }
        String listing = output.toString(StandardCharsets.UTF_8);
        assertTrue(listing.contains("1. incre000001"));
        assertTrue(listing.contains("2. incre000002 (not listed in backup.json)"));
        assertFalse(new File(group, "merged.zip").exists());
    }

    @Test
    public void mergesThroughSelectedNumberAndUsesRequestedOrDefaultOutput() throws Exception {
        File group = createBackupGroup();
        File firstOutput = new File(tmp.getRoot(), "first.zip");
        Files.writeString(firstOutput.toPath(), "previous output", StandardCharsets.UTF_8);
        assertEquals(0, CommandLine.runCommandLine(new String[]{"-d", group.getAbsolutePath(), "-t", "1",
                "-o", firstOutput.getAbsolutePath()}));
        Map<String, String> firstFiles = readZip(firstOutput);
        assertEquals("first", firstFiles.get("first.txt"));
        assertEquals("full", firstFiles.get("base.txt"));
        assertFalse(firstFiles.containsKey("second.txt"));

        File workDir = tmp.newFolder("working-directory");
        String originalDir = System.getProperty("user.dir");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setProperty("user.dir", workDir.getAbsolutePath());
            System.setOut(capture);
            assertEquals(0, CommandLine.runCommandLine(new String[]{"--group-dir=" + group.getAbsolutePath(),
                    "--merge-up-to=2"}));
        } finally {
            System.setProperty("user.dir", originalDir);
            System.setOut(originalOut);
        }
        File defaultOutput = new File(workDir, "merged.zip");
        assertTrue(defaultOutput.isFile());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains(defaultOutput.getAbsolutePath()));
        Map<String, String> secondFiles = readZip(defaultOutput);
        assertEquals("second", secondFiles.get("second.txt"));
        assertFalse(secondFiles.containsKey("first.txt"));
        assertEquals("full", secondFiles.get("base.txt"));
    }

    @Test
    public void rejectsInvalidOptionCombinationsAndOutOfRangeNumber() throws Exception {
        File group = createBackupGroup();
        assertEquals(2, CommandLine.runCommandLine(new String[]{"-l"}));
        assertEquals(2, CommandLine.runCommandLine(new String[]{"-d", group.getAbsolutePath()}));
        assertEquals(2, CommandLine.runCommandLine(new String[]{"-d", group.getAbsolutePath(), "-l", "-t", "1"}));
        assertEquals(2, CommandLine.runCommandLine(new String[]{"-d", group.getAbsolutePath(), "-l", "-o", "x.zip"}));
        assertEquals(2, CommandLine.runCommandLine(new String[]{"-d", group.getAbsolutePath(), "-t", "0"}));
        assertEquals(2, CommandLine.runCommandLine(new String[]{"-d", group.getAbsolutePath(), "-t", "3"}));
        assertEquals(2, CommandLine.runCommandLine(new String[]{"-d", group.getAbsolutePath(), "-t", "1",
                "-o", new File(group, "full.zip").getAbsolutePath()}));
        assertEquals(0, CommandLine.runCommandLine(new String[]{"--help"}));
        assertTrue(new File(group, "full.zip").isFile());
    }

    private File createBackupGroup() throws IOException {
        File group = tmp.newFolder();
        Files.writeString(new File(group, "backup.json").toPath(),
                "{\"incre_backups_history\":[{\"id\":\"000001\",\"time\":100}]}",
                StandardCharsets.UTF_8);
        writeZip(new File(group, "full.zip"), Map.of("base.txt", "full"));
        writeZip(new File(group, "incre000001.zip"), Map.of("first.txt", "first"));
        writeZip(new File(group, "incre000002.zip"),
                Map.of("second.txt", "second", "deleted.files", "first.txt\n"));
        return group;
    }

    private static void writeZip(File target, Map<String, String> entries) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target.toPath()))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    private static Map<String, String> readZip(File zip) throws IOException {
        Map<String, String> contents = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (contents.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8)) != null)
                    fail("Duplicate ZIP entry: " + entry.getName());
            }
        }
        return contents;
    }
}
