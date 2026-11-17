package dev.soundpanel;

import net.minecraft.util.Identifier;
import java.util.List;

final class PvpSounds {
    private PvpSounds() {}
    private static final List<SoundCatalog.Group> SECTIONS = List.of(
            group("melee", "Melee",
                    event("Critical hit", "entity.player.attack.crit", "A critical with an ordinary melee weapon."),
                    event("Player hurt", "entity.player.hurt", "Standard damage sound for you and other players."),
                    event("Strong hit", "entity.player.attack.strong", "A charged melee attack without a critical."),
                    event("Weak hit", "entity.player.attack.weak", "A melee hit before the attack is charged."),
                    event("Sweep attack", "entity.player.attack.sweep", "A sword sweep.")),
            group("crystal", "Crystals & survival",
                    event("Explosion", "entity.generic.explode", "Shared by crystals, TNT and other generic explosions."),
                    event("Totem pop", "item.totem.use", "A Totem of Undying activating."),
                    event("Item / armor break", "entity.item.break", "Shared by ordinary tools, weapons and armor when they break."),
                    event("Golden apple eating", "entity.generic.eat", "Shared with other food using the generic eating sound.")),
            group("mace", "Mace & wind",
                    event("Mace smash: air", "item.mace.smash_air", "Smash against a target in the air."),
                    event("Mace smash: ground", "item.mace.smash_ground", "Regular grounded mace smash."),
                    event("Mace smash: heavy", "item.mace.smash_ground_heavy", "Heavy grounded mace smash."),
                    event("Wind charge throw", "entity.wind_charge.throw", "Throwing a player wind charge."),
                    event("Wind charge burst", "entity.wind_charge.wind_burst", "The projectile's wind burst.")),
            group("defense", "Shield & ranged",
                    event("Shield block", "item.shield.block", "A shield blocking an attack."),
                    event("Shield break", "item.shield.break", "A shield breaking."),
                    event("Arrow hit confirmation", "entity.arrow.hit_player", "The confirmation tone after your arrow hits a player."),
                    event("Crossbow shot", "item.crossbow.shoot", "A crossbow firing.")),
            group("xp", "XP bottles",
                    new SoundCatalog.Event(SoundCatalog.BOTTLE_THROW, "XP bottle throw", "Throwing a Bottle o' Enchanting."),
                    new SoundCatalog.Event(SoundCatalog.BOTTLE_SHATTER, "XP bottle shatter", "Separate from potion shatter when the projectile is identifiable."),
                    new SoundCatalog.Event(SoundCatalog.ORB_PICKUP, "XP pickup", "Shared with experience orbs from other sources."))
    );

    static final String QUICK_ID = "pvp:quick";
    static final List<SoundCatalog.Group> GROUPS = shortcuts();

    private static List<SoundCatalog.Group> shortcuts() {
        var quick = new java.util.ArrayList<SoundCatalog.Group>();
        quick.add(new SoundCatalog.Group(QUICK_ID, "Quick picks", List.of(
                SECTIONS.get(0).events().get(0), SECTIONS.get(0).events().get(1),
                SECTIONS.get(1).events().get(1), SECTIONS.get(1).events().get(0),
                SECTIONS.get(2).events().get(2), SECTIONS.get(3).events().get(0),
                SECTIONS.get(4).events().get(0))));
        quick.addAll(SECTIONS);
        return List.copyOf(quick);
    }

    private static SoundCatalog.Event event(String title, String path, String detail) {
        return new SoundCatalog.Event(Identifier.ofVanilla(path), title, detail);
    }

    private static SoundCatalog.Group group(String key, String title, SoundCatalog.Event... events) {
        return new SoundCatalog.Group("pvp:" + key, title, List.of(events));
    }
}
