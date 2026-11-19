package fish.ui;

import fish.model.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.event.Event;
import javafx.scene.control.Label;
import javafx.stage.*;
import static fish.ui.FxTestSupport.*;

/** Run with panel or standalone. Each run has its own state, writer and inert credential helper. */
public final class ProfileAutosaveSmoke {
    private static void check(boolean ok,String message) { if (!ok) throw new AssertionError(message); }
    private record Form(ProfilesEditor editor,Stage stage) {
        void close() { Event.fireEvent(stage,new WindowEvent(stage,WindowEvent.WINDOW_CLOSE_REQUEST)); }
    }
    private static Form open(Workspace w,boolean panel,UUID id) throws Exception {
        return fx(() -> {
            Profile p=w.profileList().stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
            if (!panel) {
                var dialog=new ProfilesDialog(w,p);
                return new Form(dialog.editor,dialog.stage());
            }
            Stage stage=w.preferencesDialog("Profiles");
            var editor=w.tutorialPreferences().profiles;
            editor.profileListView().getSelectionModel().select(p);
            return new Form(editor,stage);
        });
    }
    private static void closed(Workspace w) throws Exception {
        await(() -> w.tutorialProfileEditor()==null,"Close did not wait for the latest durable profile");
    }
    private static void run(Path root,boolean panel) throws Exception {
        Path helper=root.resolve("fixture.ps1"),calls=root.resolve("operations");
        Files.writeString(helper,"""
                $op=[Console]::ReadLine()
                $null=[Console]::ReadLine()
                $null=[Console]::ReadLine()
                $payload=[Console]::ReadLine()
                $ok=$payload -ceq [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes('synthetic sample'))
                Add-Content (Join-Path $PSScriptRoot 'operations') "$op $ok"
                if (-not (Test-Path (Join-Path $PSScriptRoot 'allow'))) { exit 1 }
                """);
        StateStore store=new StateStore(root);
        Profile original=new Profile(UUID.randomUUID(),"Custom","host.invalid",992,"","USER",false,true,"500",
                "Mint","None",1.25,Map.of(),"",null,Profile.Authentication.PASSWORD,"service.example.invalid");
        var state=new StateStore.State(List.of(original),new Layout.Leaf(UUID.randomUUID(),original.id()),1000,700);
        store.save(state);
        AtomicBoolean fail=new AtomicBoolean();
        CountDownLatch ended=new CountDownLatch(1);
        var app=fx(() -> {
            var a=new ApplicationWindows(store,helper,true,false,Workspace::new,s -> {
                if (fail.get()) throw new IOException("injected profile write failure");
                store.save(s);
            });
            a.onClosed(ended::countDown); a.restore(state); return a;
        });
        Workspace w=fx(() -> app.windows().getFirst());
        try {
            Form first=open(w,panel,original.id());
            String name="Night & day \u03a9";
            UUID id=fx(() -> {
                ProfilesEditor e=first.editor();
                layout(first.stage());
                check(e.deviceField().getScene()==first.stage().getScene() && e.deviceField().isVisible(),"Missing inline device");
                for (String hidden:List.of("Port","Kerberos server","Code page","Advanced"))
                    check(children(e).stream().noneMatch(n -> n instanceof Label l && hidden.equals(l.getText())),
                            "Obsolete profile field: "+hidden);
                e.addButton().fire();
                UUID created=e.profileListView().getSelectionModel().getSelectedItem().id();
                e.nameField().clear();
                for (char c:name.toCharArray()) e.nameField().appendText(String.valueOf(c));
                e.deviceField().setText("dsp01");
                first.close(); return created;
            });
            closed(w);
            var created=store.load().profiles();
            check(created.size()==2 && created.get(1).id().equals(id) && created.get(1).name().equals(name)
                    && created.get(1).device().equals("DSP01"),"Immediate close lost characters or duplicated the new profile");
            Form second=open(w,panel,id);
            fxRun(() -> {
                check(second.editor().nameField().getText().equals(name),"Reopen lost rename");
                second.editor().addProfile(true);
                second.close();
            });
            closed(w);
            var copies=store.load().profiles();
            check(copies.size()==3 && copies.stream().map(Profile::id).distinct().count()==3
                    && copies.get(2).name().equals(name+" copy"),"Duplicate did not persist exactly once");

            Form edit=open(w,panel,original.id());
            ProfilesEditor e=edit.editor();
            fail.set(true);
            fxRun(() -> { e.nameField().setText("New custom name"); edit.close(); });
            await(e::failedSave,"Disk failure not reported");
            check(fx(() -> w.tutorialProfileEditor()==e && e.nameField().getText().equals("New custom name")),
                    "Failure closed editor or lost edit");
            check(store.load().profiles().getFirst().equals(original),"Failed write replaced last good state");
            fail.set(false);
            fxRun(() -> e.saveButton().fire());
            await(() -> !e.saving() && !e.failedSave(),"Retry did not finish");
            Profile saved=store.load().profiles().getFirst();
            check(saved.name().equals("New custom name") && saved.port()==original.port() && saved.codePage().equals(original.codePage())
                    && saved.kerberosServer().equals(original.kerberosServer()),"Rename reset hidden custom connection values");

            fxRun(() -> {
                e.hostField().setText("other.invalid");
                e.passwordField().appendText("synthetic");
                e.passwordField().appendText(" sample");
                e.deviceField().setText("DSP02");
            });
            await(() -> !e.saving(),"Safe changes while editing a password did not persist");
            saved=store.load().profiles().getFirst();
            check(!Files.exists(calls) && saved.host().equals(original.host()) && saved.device().equals("DSP02"),
                    "Typing touched credentials/retargeted login or blocked unrelated edits");
            fxRun(() -> e.saveButton().fire());
            await(() -> !e.saving(),"Injected credential failure did not finish");
            check(fx(() -> e.error().contains("not saved") && e.passwordField().getText().equals("synthetic sample"))
                    && Files.readAllLines(calls).equals(List.of("write True")),"Failed login save lost draft or wrote a partial password");
            Files.createFile(root.resolve("allow"));
            fail.set(true);
            fxRun(() -> e.saveButton().fire());
            await(e::failedSave,"Login change disk failure not surfaced");
            fail.set(false);
            fxRun(() -> { e.saveButton().fire(); edit.close(); });
            closed(w);
            saved=store.load().profiles().getFirst();
            check(saved.host().equals("other.invalid") && saved.kerberosServer().equals(original.kerberosServer())
                    && Files.readAllLines(calls).equals(List.of("write True","write True","delete False")),
                    "Disk retry lost login change or replayed credential operations");
        } finally {
            fail.set(false);
            fxRun(app::quit);
            check(ended.await(10,TimeUnit.SECONDS),"Fixture writer did not close");
        }
    }
    public static void main(String[] args) throws Exception {
        Ui.install();
        try {
            for (String mode:args.length==0 ? List.of("panel","standalone") : Arrays.asList(args)) {
                if (!List.of("panel","standalone").contains(mode)) throw new IllegalArgumentException(mode);
                Path root=Files.createTempDirectory(Path.of("build"),"profile-"+mode+"-");
                try { run(root,mode.equals("panel")); System.out.println("PASS profile autosave: "+mode); }
                finally { deleteTree(root); }
            }
        } finally { Ui.finishTestRun(); }
    }
}
