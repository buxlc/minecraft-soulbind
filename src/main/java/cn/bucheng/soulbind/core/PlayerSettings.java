package cn.bucheng.soulbind.core;

/** Persistent per-owner policy, also used while the owner is offline. */
public final class PlayerSettings {
    private boolean allowDrop;
    private boolean autoReturn = true;
    private boolean allowOthersPickup;
    private final Runnable changed;

    PlayerSettings(Runnable changed) { this.changed = changed; }

    public boolean allowDrop() { return allowDrop; }
    public boolean autoReturn() { return autoReturn; }
    public boolean allowOthersPickup() { return allowOthersPickup; }

    public void setAllowDrop(boolean value) { update(value, autoReturn, allowOthersPickup); }
    public void setAutoReturn(boolean value) { update(allowDrop, value, allowOthersPickup); }
    public void setAllowOthersPickup(boolean value) { update(allowDrop, autoReturn, value); }

    public void update(boolean drop, boolean recall, boolean pickup) {
        boolean oldDrop = allowDrop, oldRecall = autoReturn, oldPickup = allowOthersPickup;
        load(drop, recall, pickup);
        try { changed.run(); }
        catch (RuntimeException failure) {
            load(oldDrop, oldRecall, oldPickup);
            throw failure;
        }
    }

    void load(boolean drop, boolean recall, boolean pickup) {
        allowDrop = drop;
        autoReturn = recall;
        allowOthersPickup = pickup;
    }
}
