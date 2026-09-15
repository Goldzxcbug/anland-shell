package com.anland.shell.ds;

/** One Droidspaces container: name, config path, running state + init pid. */
public final class ContainerState {

    public final String name;
    public final String configPath;
    public int pid = -1;

    public ContainerState(String name, String configPath) {
        this.name = name;
        this.configPath = configPath;
    }

    public boolean running() {
        return pid > 0;
    }

    @Override public String toString() {
        return name;
    }
}
