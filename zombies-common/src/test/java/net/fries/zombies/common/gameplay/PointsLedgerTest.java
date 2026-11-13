package net.fries.zombies.common.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import net.fries.zombies.common.NamespacedId;
import net.fries.zombies.common.content.Gameplay;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

class PointsLedgerTest {
    Gameplay content;
    Survivor a, b;
    Map<UUID, Survivor> roster;
    PointsLedger ledger;

    @BeforeEach
    void setup() throws Exception {
        content = Gameplay.load(Path.of("../zombies-game/src/main/resources/gameplay.yml"));
        a = new Survivor(UUID.randomUUID(), content);
        b = new Survivor(UUID.randomUUID(), content);
        roster = Map.of(a.id(), a, b.id(), b);
        ledger = new PointsLedger(content.scoring(), roster);
    }

    Gameplay.Weapon gun(String name) {
        return content.weapons().get(new NamespacedId("friesnet:weapon/" + name));
    }

    UUID enemy() {
        var id = UUID.randomUUID();
        ledger.register(id);
        return id;
    }

    int hit(
            UUID attack,
            UUID enemy,
            Survivor player,
            long tick,
            PointsLedger.Kind kind,
            boolean head,
            double before,
            double after,
            String weapon) {
        return ledger.damage(
                new PointsLedger.Damage(
                        attack, enemy, player.id(), tick, kind, head, before, after),
                gun(weapon));
    }

    int bullet(UUID enemy, boolean head, double before, double after) {
        return hit(
                UUID.randomUUID(),
                enemy,
                a,
                0,
                PointsLedger.Kind.BULLET,
                head,
                before,
                after,
                "glock18");
    }

    @Test
    void bodyHeadAndLethalHitsHaveNoImplicitKillBonus() {
        var e = enemy();
        assertEquals(10, bullet(e, false, 30, 24));
        assertEquals(15, bullet(e, true, 24, 12));
        assertEquals(15, bullet(e, true, 12, 0));
        assertEquals(540, a.points());
        assertEquals(40, ledger.paid(e));
    }

    @Test
    void invalidDamageMissesInvulnerabilityAndDeadTargetsPayNothing() {
        var e = enemy();
        assertEquals(0, bullet(e, false, 20, 20));
        assertEquals(0, bullet(e, false, 0, 0));
        assertEquals(0, bullet(e, false, Double.NaN, 5));
        assertEquals(0, bullet(UUID.randomUUID(), false, 20, 10));
        assertEquals(10, bullet(e, false, 20, 0));
        assertEquals(0, bullet(e, true, 20, 0));
        assertEquals(510, a.points());
    }

    @Test
    void duplicatesAndPiercingUseOneAwardPerDistinctEnemy() {
        var one = enemy();
        var two = enemy();
        var attack = UUID.randomUUID();
        assertEquals(
                10, hit(attack, one, a, 1, PointsLedger.Kind.BULLET, false, 30, 24, "glock18"));
        assertEquals(0, hit(attack, one, a, 1, PointsLedger.Kind.BULLET, false, 24, 18, "glock18"));
        assertEquals(
                10, hit(attack, two, a, 1, PointsLedger.Kind.BULLET, false, 30, 24, "glock18"));
        assertEquals(520, a.points());
        assertEquals(0, bullet(two, false, 30, 24));
    }

    @Test
    void eachWeaponOwnsItsHitPrices() {
        assertEquals(
                7,
                hit(
                        UUID.randomUUID(),
                        enemy(),
                        a,
                        0,
                        PointsLedger.Kind.BULLET,
                        false,
                        100,
                        90,
                        "ak47"));
        assertEquals(
                10,
                hit(
                        UUID.randomUUID(),
                        enemy(),
                        a,
                        0,
                        PointsLedger.Kind.BULLET,
                        true,
                        100,
                        80,
                        "ak47"));
        assertEquals(
                4,
                hit(
                        UUID.randomUUID(),
                        enemy(),
                        a,
                        0,
                        PointsLedger.Kind.BULLET,
                        false,
                        100,
                        95,
                        "mac10"));
        assertEquals(
                24,
                hit(
                        UUID.randomUUID(),
                        enemy(),
                        a,
                        0,
                        PointsLedger.Kind.BULLET,
                        true,
                        100,
                        64,
                        "revolver"));
        assertEquals(
                30,
                hit(
                        UUID.randomUUID(),
                        enemy(),
                        a,
                        0,
                        PointsLedger.Kind.BULLET,
                        true,
                        100,
                        25,
                        "awp"));
    }

    @Test
    void meleeExplosionAndDotUseIndependentRules() {
        assertEquals(
                10,
                hit(
                        UUID.randomUUID(),
                        enemy(),
                        a,
                        0,
                        PointsLedger.Kind.MELEE,
                        false,
                        30,
                        24,
                        "glock18"));
        var attack = UUID.randomUUID();
        assertEquals(
                5, hit(attack, enemy(), a, 0, PointsLedger.Kind.EXPLOSIVE, false, 30, 20, "awp"));
        assertEquals(
                5, hit(attack, enemy(), a, 0, PointsLedger.Kind.EXPLOSIVE, false, 30, 20, "awp"));
        assertEquals(
                0,
                hit(
                        UUID.randomUUID(),
                        enemy(),
                        a,
                        0,
                        PointsLedger.Kind.DAMAGE_OVER_TIME,
                        false,
                        30,
                        28,
                        "awp"));
    }

    @Test
    void healedEnemiesAndLifetimeCapsCannotProduceUnlimitedGold() {
        var p = content.scoring();
        ledger =
                new PointsLedger(
                        new PointsPolicy(false, 10, 5, 0, 0, 0, 600, 100, 25, 600, 0), roster);
        var e = enemy();
        assertEquals(10, bullet(e, false, 100, 90));
        assertEquals(0, bullet(e, false, 100, 95));
        assertEquals(15, bullet(e, true, 95, 80));
        assertEquals(0, bullet(e, false, 80, 70));
        assertEquals(25, ledger.paid(e));
    }

    @Test
    void assistsAndOptionalLethalBonusesRespectParticipationAndWindow() {
        ledger =
                new PointsLedger(
                        new PointsPolicy(false, 10, 5, 0, 3, 4, 20, 100, 300, 600, 0), roster);
        var e = enemy();
        bullet(e, false, 30, 24);
        assertEquals(
                13,
                hit(
                        UUID.randomUUID(),
                        e,
                        b,
                        10,
                        PointsLedger.Kind.BULLET,
                        false,
                        24,
                        0,
                        "glock18"));
        assertEquals(514, a.points());
        assertEquals(513, b.points());
        var other = enemy();
        bullet(other, false, 30, 24);
        hit(UUID.randomUUID(), other, b, 30, PointsLedger.Kind.BULLET, false, 24, 0, "glock18");
        assertEquals(524, a.points());
    }

    @Test
    void doubleGoldExpiresOnMatchTicksAndNeverChangesPermanentBalances() {
        ledger.doubleGold(0, 20);
        var e = enemy();
        assertEquals(20, bullet(e, false, 100, 90));
        assertEquals(
                30,
                hit(
                        UUID.randomUUID(),
                        e,
                        a,
                        19,
                        PointsLedger.Kind.BULLET,
                        true,
                        90,
                        70,
                        "glock18"));
        assertEquals(
                10,
                hit(
                        UUID.randomUUID(),
                        e,
                        a,
                        20,
                        PointsLedger.Kind.BULLET,
                        false,
                        70,
                        60,
                        "glock18"));
        assertEquals(0, ledger.doubleGoldRemaining(20));
        assertEquals(0, a.result(content, false).tokens());
        assertEquals(0, a.result(content, false).experience());
    }

    @Test
    void repairBoardIdentityIsSharedAndRoundCapsAreEnforced() {
        assertEquals(0, ledger.repair(a.id(), "north", "one", 1, false, 10, 15, 0));
        assertEquals(10, ledger.repair(a.id(), "north", "one", 1, true, 10, 15, 0));
        assertEquals(0, ledger.repair(b.id(), "north", "one", 1, true, 10, 15, 0));
        assertEquals(5, ledger.repair(a.id(), "north", "two", 1, true, 10, 15, 0));
        assertEquals(0, ledger.repair(a.id(), "north", "three", 1, true, 10, 15, 0));
        assertEquals(10, ledger.repair(a.id(), "north", "one", 2, true, 10, 15, 0));
        assertEquals(525, a.points());
    }

    @Test
    void objectiveCompletionIsIdempotentAndConfigurable() {
        assertEquals(100, ledger.objective(a.id(), "power", 1, 0));
        assertEquals(0, ledger.objective(a.id(), "power", 1, 0));
        ledger.doubleGold(0, 20);
        assertEquals(200, ledger.objective(a.id(), "power", 2, 1));
    }

    @Test
    void leavingAndUnknownPlayersCannotFarm() {
        var e = enemy();
        a.leave();
        assertEquals(0, bullet(e, false, 30, 24));
        assertEquals(0, ledger.repair(a.id(), "north", "one", 1, true, 10, 60, 0));
        assertEquals(0, ledger.objective(UUID.randomUUID(), "power", 1, 0));
    }

    @Test
    void defaultScoringDisablesFixedKillAndRoundGoldWhileKeepingResults() {
        a.kill(true, content);
        a.round(content);
        assertEquals(500, a.points());
        assertEquals(1, a.result(content, false).tokens());
        assertEquals(15, a.result(content, false).experience());
    }
}
