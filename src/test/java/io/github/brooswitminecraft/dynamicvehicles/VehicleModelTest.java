package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-443 review fix (comment 48829): {@link VehicleModel#forId} is the exact production selection
 * {@link CarRenderer#render} and {@code CarEntity#hitBlockAhead} key their model/collision behaviour on,
 * extracted so it is directly, headlessly testable -- no Minecraft/Sable bootstrap, no client renderer.
 */
class VehicleModelTest {

    @Test
    void everyKnownIdMapsToItsOwnDistinctModel() {
        assertEquals(VehicleModel.CAR, VehicleModel.forId(VehicleIds.CAR));
        assertEquals(VehicleModel.TRUCK, VehicleModel.forId(VehicleIds.TRUCK));
        assertEquals(VehicleModel.TROPHY, VehicleModel.forId(VehicleIds.TROPHY_TRUCK));
        assertEquals(VehicleModel.DRIFT, VehicleModel.forId(VehicleIds.DRIFT_CAR));
        assertEquals(VehicleModel.RALLY, VehicleModel.forId(VehicleIds.RALLY_CAR));
        assertEquals(VehicleModel.MUSCLE, VehicleModel.forId(VehicleIds.MUSCLE_CAR));
        assertEquals(VehicleModel.ROCK_CRAWLER, VehicleModel.forId(VehicleIds.ROCK_CRAWLER));
        assertEquals(VehicleModel.MONSTER_TRUCK, VehicleModel.forId(VehicleIds.MONSTER_TRUCK));
        assertEquals(VehicleModel.INDY, VehicleModel.forId(VehicleIds.INDY_CAR));
        assertEquals(VehicleModel.BUS, VehicleModel.forId(VehicleIds.BUS));
        assertEquals(VehicleModel.CARGO_TRUCK, VehicleModel.forId(VehicleIds.CARGO_TRUCK));
    }

    @Test
    void anUnrecognisedIdFallsBackRatherThanMatchingAnyRealCar() {
        assertEquals(VehicleModel.FALLBACK, VehicleModel.forId("some_future_vehicle_not_yet_given_a_look"));
    }

    /**
     * The reviewed bug's exact shape: an overridden {@link VehicleSpec} is a different object from, and
     * may hold arbitrary field values unlike, any stock constant. {@link VehicleModel#forId} takes only
     * the id string -- never a {@link VehicleSpec} at all -- so it is structurally impossible for a
     * config override (which only ever changes tuning fields, never the id {@code CarEntity#vehicleId()}
     * reports) to flip a truck's model/collision selection to a non-truck one, or vice versa, regardless
     * of what the overridden spec's own fields happen to equal.
     */
    @Test
    void truckAndNonTruckDecisionsStayDistinctRegardlessOfAnOverriddenSpecsFieldValues() {
        // An overridden car's spec is neither reference-equal nor field-equal to any stock constant
        // (VehicleSpecOverrides builds a brand-new VehicleSpec instance whenever a file changes anything)
        // -- VehicleModel#forId's signature takes only the id string, never a VehicleSpec, so there is
        // literally no spec value this call could read even if one were passed; the id alone decides.
        assertEquals(VehicleModel.TRUCK, VehicleModel.forId(VehicleIds.TRUCK),
                "a truck's model/collision selection must stay TRUCK regardless of its spec's own field values");
        assertNotEquals(VehicleModel.TRUCK, VehicleModel.forId(VehicleIds.CAR),
                "a non-truck car must never be selected as TRUCK");
    }
}
