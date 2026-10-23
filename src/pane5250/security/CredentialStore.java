package pane5250.security;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import pane5250.model.Profile;

/** Secrets cross stdin/stdout only, never command arguments, XML, disk or the clipboard. */
public final class CredentialStore {
    private final Path helper;
    public CredentialStore(Path helper) { this.helper = helper; }
    public Optional<char[]> read(Profile profile) throws IOException {
        String result = execute("read", profile, new char[0]);
        if (result.equals("MISSING")) return Optional.empty();
        if (!result.startsWith("FOUND ")) throw new IOException("Credential helper returned an invalid response.");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(result.substring(6)); }
        catch (IllegalArgumentException e) { throw new IOException("Credential helper returned invalid encoding.", e); }
        char[] password = StandardCharsets.UTF_16LE.decode(java.nio.ByteBuffer.wrap(bytes)).toString().toCharArray();
        Arrays.fill(bytes, (byte) 0);
        return Optional.of(password);
    }
    public void write(Profile profile, char[] password) throws IOException { execute("write", profile, password); }
    public void delete(Profile profile) throws IOException { execute("delete", profile, new char[0]); }

    private String execute(String operation, Profile profile, char[] password) throws IOException {
        if (!System.getProperty("os.name").startsWith("Windows"))
            throw new IOException("Remembered credentials require Windows Credential Manager.");
        if (!Files.isRegularFile(helper)) throw new IOException("Credential helper is missing: " + helper);
        Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-File", helper.toAbsolutePath().toString()).start();
        byte[] bytes = new String(password).getBytes(StandardCharsets.UTF_16LE);
        try (Writer stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            stdin.write(operation + "\n" + profile.credentialTarget() + "\n" + profile.username() + "\n");
            stdin.write(Base64.getEncoder().encodeToString(bytes));
            stdin.write("\n");
        } finally { Arrays.fill(bytes, (byte) 0); }
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Windows Credential Manager operation timed out.");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Credential operation interrupted.", e);
        }
        if (process.exitValue() != 0)
            throw new IOException("Windows Credential Manager failed: " +
                    new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip());
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    }
}
