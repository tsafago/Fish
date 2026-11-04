package pane5250.model;

import java.util.*;

public final class ProfileManagement {
    private ProfileManagement() {}
    public static List<Profile> move(List<Profile> profiles,UUID id,int direction) {
        if (direction!=1 && direction!=-1) throw new IllegalArgumentException("Move one place up or down.");
        List<Profile> result=new ArrayList<>(profiles);
        int index=-1;
        for (int i=0;i<result.size();i++) if (result.get(i).id().equals(id)) index=i;
        if (index<0) throw new IllegalArgumentException("Save this profile before moving it.");
        int target=index+direction;
        if (target>=0 && target<result.size()) Collections.swap(result,index,target);
        return List.copyOf(result);
    }
    public static StateStore.State delete(StateStore.State state,UUID id) {
        if (state.profiles().stream().noneMatch(p -> p.id().equals(id)))
            throw new IllegalArgumentException("This profile is not saved.");
        if (state.profiles().size()==1)
            throw new IllegalArgumentException("Keep at least one profile. Create a replacement first, or use Factory defaults to start over.");
        List<String> dependents=state.profiles().stream().filter(p -> id.equals(p.loginSource())).map(Profile::name).toList();
        if (!dependents.isEmpty())
            throw new IllegalArgumentException("This login is used by: "+String.join(", ",dependents)+". Choose an independent or different login in those profiles first.");
        UUID replacement=state.profiles().stream().filter(p -> !p.id().equals(id)).findFirst().orElseThrow().id();
        List<Profile> profiles=state.profiles().stream().filter(p -> !p.id().equals(id))
                .map(p -> p.savedLayout().isEmpty() ? p : p.withLayout(Layout.decode(p.savedLayout()).replaceProfile(id,replacement).encode())).toList();
        return new StateStore.State(profiles,state.layout().replaceProfile(id,replacement),state.width(),state.height(),state.appTheme(),
                state.lastProfile().equals(id) ? replacement : state.lastProfile(),state.shortcuts(),state.highlightActive(),
                state.appearance(),state.tutorial(),state.connectOnStartup(),state.updates());
    }
}
