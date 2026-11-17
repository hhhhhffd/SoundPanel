package dev.soundpanel;

import net.minecraft.util.Identifier;

/** Context exists only for the synchronous world event hook and only affects its shatter sound. */
public final class BottleImpact {
    private static final ThreadLocal<Boolean> MATCHED = new ThreadLocal<>();
    private BottleImpact() {}
    public static boolean hasAssignment() { return SoundPanel.CONFIG.mappings().containsKey(SoundCatalog.BOTTLE_SHATTER.toString()); }

    public static void run(boolean bottle, Runnable action) {
        Boolean previous = MATCHED.get();
        if (bottle) MATCHED.set(Boolean.TRUE); else MATCHED.remove();
        try { action.run(); }
        finally { if (previous == null) MATCHED.remove(); else MATCHED.set(previous); }
    }
    static Identifier replacementId(Identifier rawId) {
        return MATCHED.get() != null && SoundCatalog.SPLASH_BREAK.equals(rawId) ? SoundCatalog.BOTTLE_SHATTER : null;
    }
}
