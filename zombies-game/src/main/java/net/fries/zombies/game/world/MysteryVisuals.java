package net.fries.zombies.game.world;

import net.fries.zombies.common.content.MysteryPresentation;

import org.bukkit.*;
import org.bukkit.block.Lidded;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Disposable renderer. It has no gold, ownership, random reward selection or inventory access. */
public final class MysteryVisuals {
    private final Location origin;
    private final MysteryPresentation settings;
    private final ItemDisplay display;
    private final Lidded lid;
    private final float yaw;
    private final long started;
    private long closing = -1;
    private boolean revealed;

    public MysteryVisuals(
            Location origin,
            Location chest,
            MysteryPresentation settings,
            float yaw,
            long started) {
        this.origin = origin.clone();
        this.settings = settings;
        this.yaw = yaw;
        this.started = started;
        var state = chest.getBlock().getState();
        lid = state instanceof Lidded l ? l : null;
        display =
                origin.getWorld()
                        .spawn(
                                origin.clone().add(0, settings.height() - .3, 0),
                                ItemDisplay.class,
                                d -> {
                                    d.setPersistent(false);
                                    d.setBillboard(Display.Billboard.FIXED);
                                    d.setItemDisplayTransform(
                                            ItemDisplay.ItemDisplayTransform.FIXED);
                                    d.setInterpolationDuration(2);
                                    d.setTeleportDuration(2);
                                    d.setViewRange(.5f);
                                });
        pose(settings.scale() * .05, 0);
    }

    public void activate() {
        if (lid != null) lid.open();
        origin.getWorld().playSound(origin, Sound.BLOCK_CHEST_OPEN, .65f, .85f);
    }

    private void pose(double scale, float angle) {
        display.setInterpolationDelay(0);
        display.setTransformation(
                new Transformation(
                        new Vector3f(),
                        new Quaternionf().rotateY((float) Math.toRadians(yaw + angle)),
                        new Vector3f((float) scale),
                        new Quaternionf()));
    }

    public void item(ItemStack item) {
        if (display.isValid()) display.setItemStack(item);
    }

    public void cycle(double progress) {
        if (display.isValid())
            origin.getWorld()
                    .playSound(
                            origin,
                            Sound.BLOCK_NOTE_BLOCK_HAT,
                            .25f,
                            (float) (1.8 - progress * .8));
    }

    public void reveal(boolean rare) {
        if (revealed) return;
        revealed = true;
        if (!display.isValid()) return;
        display.setGlowing(rare);
        origin.getWorld()
                .playSound(
                        origin,
                        rare ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.BLOCK_NOTE_BLOCK_CHIME,
                        .55f,
                        1.1f);
        if (rare && settings.rareParticles())
            origin.getWorld()
                    .spawnParticle(
                            Particle.FIREWORK,
                            origin.clone().add(0, settings.height(), 0),
                            12,
                            .25,
                            .3,
                            .25,
                            .02);
    }

    public void close(long tick) {
        if (closing < 0) closing = tick;
    }

    public boolean tick(long tick, int openTicks) {
        if (!display.isValid()) return false;
        double rise = settings.rise(tick - started, openTicks), scale = settings.scale();
        if (closing >= 0) {
            double remaining = 1 - (tick - closing) / (double) settings.closeTicks();
            if (remaining <= 0) {
                remove();
                return false;
            }
            scale *= remaining;
            rise = remaining;
        } else if (!revealed) scale *= Math.max(.05, rise);
        var at = origin.clone().add(0, settings.height() - .3 + .3 * rise, 0);
        display.teleport(at);
        pose(scale, revealed ? 0 : (float) Math.sin((tick - started) * .08) * 6);
        if (tick % 10 == 0 && closing < 0)
            origin.getWorld().spawnParticle(Particle.ENCHANT, at, 2, .15, .15, .15, 0);
        return true;
    }

    public void remove() {
        display.remove();
        if (lid != null) lid.close();
    }

    public ItemDisplay display() {
        return display;
    }

    public static ItemStack preview(
            net.fries.zombies.common.content.Gameplay.Weapon weapon, boolean models) {
        var item = new ItemStack(Material.valueOf(weapon.material()));
        item.editMeta(
                meta -> {
                    meta.displayName(net.kyori.adventure.text.Component.text(weapon.name()));
                    if (models)
                        meta.setItemModel(
                                NamespacedKey.fromString(weapon.behaviour().asset().value()));
                });
        return item;
    }
}
