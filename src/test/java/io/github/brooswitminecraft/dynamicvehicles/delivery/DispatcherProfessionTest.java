package io.github.brooswitminecraft.dynamicvehicles.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.block.Blocks;

/**
 * Covers what AC7 of MINECRAFT-105 asks for without a running client: that the
 * {@code dispatcher} profession is built correctly and that its job-site predicates
 * resolve against the real vanilla POI registry the way {@link DispatcherProfession}'s
 * javadoc claims (bound to {@code PoiTypes.MEETING}, i.e. the Bell, and nothing else).
 *
 * <p>Referencing {@link BuiltInRegistries} triggers vanilla's own static bootstrap
 * (block/POI/profession registration) with no NeoForge mod loader or client involved,
 * so {@link PoiTypes#MEETING} and the other vanilla POI holders used here are real,
 * fully-populated registry entries rather than stubs.
 */
class DispatcherProfessionTest {
    private static Holder<PoiType> holderFor(ResourceKey<PoiType> key) {
        return BuiltInRegistries.POINT_OF_INTEREST_TYPE.getHolderOrThrow(key);
    }

    @Test
    void nameMatchesTheRegisteredPath() {
        VillagerProfession dispatcher = DispatcherProfession.create();
        assertEquals("dispatcher", dispatcher.name());
    }

    @Test
    void jobSitePredicatesAcceptTheVanillaMeetingPoiType() {
        VillagerProfession dispatcher = DispatcherProfession.create();
        Holder<PoiType> meeting = holderFor(PoiTypes.MEETING);

        assertTrue(dispatcher.heldJobSite().test(meeting), "a Dispatcher must recognize the Bell/meeting POI as its held job site");
        assertTrue(dispatcher.acquirableJobSite().test(meeting), "an unemployed villager must be able to acquire the Bell/meeting POI as Dispatcher");
    }

    @Test
    void jobSitePredicatesRejectOtherVanillaPoiTypes() {
        VillagerProfession dispatcher = DispatcherProfession.create();

        for (ResourceKey<PoiType> key : List.of(PoiTypes.ARMORER, PoiTypes.FARMER, PoiTypes.LIBRARIAN, PoiTypes.HOME)) {
            Holder<PoiType> other = holderFor(key);
            assertFalse(dispatcher.heldJobSite().test(other), () -> key + " must not satisfy the Dispatcher's held job site");
            assertFalse(dispatcher.acquirableJobSite().test(other), () -> key + " must not satisfy the Dispatcher's acquirable job site");
        }
    }

    @Test
    void dispatcherOffersNothingInThisSlice() {
        VillagerProfession dispatcher = DispatcherProfession.create();
        assertTrue(dispatcher.requestedItems().isEmpty(), "no trades/offers belong to this slice");
        assertTrue(dispatcher.secondaryPoi().isEmpty(), "the Bell is the only POI the Dispatcher uses");
    }

    @Test
    void bellBlockStatesAreStillExclusivelyOwnedByTheVanillaMeetingPoiType() {
        // Guards the central risk this profession is reviewed hardest on: nothing in
        // this mod may claim the Bell's BlockStates for a type other than vanilla's
        // own MEETING PoiType (PoiTypes#registerBlockStates throws if a BlockState is
        // ever mapped to more than one PoiType, so this would fail loudly if it broke).
        for (var state : Blocks.BELL.getStateDefinition().getPossibleStates()) {
            assertTrue(PoiTypes.forState(state).map(h -> h.is(PoiTypes.MEETING)).orElse(false), () -> state + " must map to the vanilla MEETING PoiType");
        }
    }
}
