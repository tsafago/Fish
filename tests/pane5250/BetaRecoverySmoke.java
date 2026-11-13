package pane5250.ui;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import pane5250.model.*;

/** Locked, explicit startup recovery; only synthetic workspace bytes in private temporary folders. */
public final class BetaRecoverySmoke {
    private static int checks;
    private static void check(boolean value,String message) { checks++; if (!value) throw new AssertionError(message); }
    private static byte[] unsupported() throws Exception {
        Properties p=StateStore.properties(StateStore.freshState()); p.setProperty("version","999");
        ByteArrayOutputStream out=new ByteArrayOutputStream(); p.storeToXML(out,"Synthetic unsupported format","UTF-8"); return out.toByteArray();
    }
    private static class Decisions implements WorkspaceRecovery.Decisions {
        WorkspaceRecovery.Choice choice=WorkspaceRecovery.Choice.FACTORY;
        boolean confirm=true; int prompts,confirmations,failures;
        String error;
        @Override public WorkspaceRecovery.Choice choose(String message,boolean retry) {
            prompts++; error=message;
            if (prompts>1) { check(!retry,"Retry offered at most once"); return WorkspaceRecovery.Choice.EXIT; }
            return choice;
        }
        @Override public boolean confirm() { confirmations++; return confirm; }
        @Override public void failed(String message) { failures++; error=message; }
    }
    private static List<Path> backups(Path directory) throws Exception {
        try (var files=Files.list(directory)) { return files.filter(p -> p.getFileName().toString().startsWith("workspace-recovery-")).toList(); }
    }
    private static void corrupt(Path root,byte[] bytes) throws Exception {
        Path directory=Files.createDirectory(root),file=directory.resolve("workspace.xml");
        Files.write(file,bytes);
        Files.writeString(directory.resolve("user-font.txt"),"Synthetic unrelated file");
        try (StateStore store=new StateStore(directory)) {
            Decisions exit=new Decisions(); exit.choice=WorkspaceRecovery.Choice.EXIT;
            check(WorkspaceRecovery.load(store,exit)==null && exit.confirmations==0,"Exit is safe and never resets implicitly");
            check(Arrays.equals(bytes,Files.readAllBytes(file)) && backups(directory).isEmpty(),"Exit preserves exact original bytes");
            Decisions cancel=new Decisions(); cancel.confirm=false;
            check(WorkspaceRecovery.load(store,cancel)==null && cancel.confirmations==1,"Factory recovery has a separate confirmation boundary");
            check(Arrays.equals(bytes,Files.readAllBytes(file)) && backups(directory).isEmpty(),"Cancelled confirmation writes no backup or replacement");
            Decisions retry=new Decisions(); retry.choice=WorkspaceRecovery.Choice.RETRY;
            check(WorkspaceRecovery.load(store,retry)==null && retry.prompts==2,"Failed retry is bounded, not a dialog loop");
            try { new StateStore(directory); throw new AssertionError("Concurrent owner admitted"); }
            catch (StateStore.InUseException expected) { checks++; }
            Decisions reset=new Decisions();
            StateStore.State fresh=WorkspaceRecovery.load(store,reset);
            check(fresh!=null && fresh.profiles().size()==1 && fresh.savedLayouts().isEmpty() && fresh.shortcuts().isEmpty()
                    && fresh.appearance().cursorStyle()==Appearance.CursorStyle.IBM && fresh.tutorial()==StateStore.Tutorial.NEW,
                    "Confirmed recovery saves actual factory invariants");
            List<Path> retained=backups(directory);
            check(retained.size()==1 && Arrays.equals(bytes,Files.readAllBytes(retained.getFirst())),"Exact failed/future input is recoverable, not relabeled");
            check(Files.readString(directory.resolve("user-font.txt")).equals("Synthetic unrelated file"),"Recovery never cleans other user files");
            check(store.load().equals(fresh),"Recovered workspace is durably readable");
            if (root.getFileName().toString().equals("future")) check(reset.error.contains("999"),"Future version explanation names incompatibility");
        }
    }
    private static void failures(Path root) throws Exception {
        Path directory=Files.createDirectory(root),file=directory.resolve("workspace.xml");
        byte[] original="<truncated".getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(file,original);
        try (StateStore store=new StateStore(directory)) {
            Files.writeString(file,"changed externally");
            Decisions external=new Decisions();
            check(WorkspaceRecovery.load(store,external)==null && external.failures==1,"External file change blocks recovery rather than bypassing stale-write safety");
            check(Files.readString(file).equals("changed externally") && backups(directory).isEmpty(),"Externally changed file remains intact");
            Files.write(file,original);
            AclFileAttributeView acl=Files.getFileAttributeView(directory,AclFileAttributeView.class);
            if (acl!=null) {
                List<AclEntry> permissions=acl.getAcl();
                UserPrincipal owner=Files.getOwner(directory);
                List<AclEntry> denied=new ArrayList<>();
                denied.add(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(owner)
                        .setPermissions(AclEntryPermission.ADD_FILE,AclEntryPermission.ADD_SUBDIRECTORY).build());
                denied.addAll(permissions); acl.setAcl(denied);
                try {
                    Decisions backupFailure=new Decisions();
                    check(WorkspaceRecovery.load(store,backupFailure)==null && backupFailure.failures==1,"Backup creation permission failure is explicit");
                    check(Arrays.equals(original,Files.readAllBytes(file)) && backups(directory).isEmpty(),"No original replacement without a successful backup");
                } finally { acl.setAcl(permissions); }
            }
            DosFileAttributeView dos=Files.getFileAttributeView(file,DosFileAttributeView.class);
            if (dos!=null) {
                dos.setReadOnly(true);
                try {
                    Decisions saveFailure=new Decisions();
                    check(WorkspaceRecovery.load(store,saveFailure)==null && saveFailure.failures==1,"Failed factory replacement stays visible, never success-shaped");
                    check(Arrays.equals(original,Files.readAllBytes(file)) && backups(directory).size()==1,
                            "Save failure retains both original and exact recovery backup");
                } finally { dos.setReadOnly(false); }
            }
        }
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory(Path.of("build"),"beta-recovery-");
        try {
            corrupt(root.resolve("malformed"),"not XML".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            corrupt(root.resolve("truncated"),"<properties>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            corrupt(root.resolve("future"),unsupported());
            corrupt(root.resolve("oversized"),new byte[2_000_001]);
            failures(root.resolve("failures"));
            System.out.println("PASS confirmed locked startup recovery / exact backups / future version / permissions / concurrent owner: "+checks);
        } finally {
            try (var files=Files.walk(root)) { for (Path p:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
}
