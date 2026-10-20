package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * MINECRAFT-443 review fix (comment 48790/48812): the reviewed regression was {@link CarRenderer} and
 * {@code CarEntity#hitBlockAhead} dispatching model/collision behaviour on {@code spec ==
 * VehicleSpec.SOME_CONSTANT} reference identity, which a per-car config override breaks (the resolved
 * spec becomes a new, merely-equal-by-value instance). The fix was to dispatch on {@code
 * CarEntity#vehicleId()} instead. A real GameTest can prove {@code vehicleId()} itself stays correct under
 * a loaded override ({@link VehicleSpecOverridesGameTests}), but {@code CarRenderer} is client-only code
 * ({@code PoseStack}/{@code MultiBufferSource}/a live {@code Minecraft} instance) that cannot run inside
 * the headless {@code gameTestServer} this repo's CI uses -- there is no way to actually invoke {@link
 * CarRenderer#render} and observe which model it drew from that harness.
 *
 * <p>This is the next best thing: a structural regression guard, source-scanning both files for the
 * literal pattern class to make sure the dispatch style itself is never reintroduced, in either file,
 * under any future car. Comment/javadoc lines (including this class's own, and {@code CarEntity}'s javadoc
 * that explains the historical bug using that exact literal) are stripped first so they cannot trip it.
 */
class VehicleSpecIdentityDispatchGuardTest {
    private static final Pattern IDENTITY_DISPATCH = Pattern.compile("spec\\s*==\\s*VehicleSpec\\.");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//.*");

    @Test
    void carRendererNeverDispatchesOnSpecIdentity() throws IOException {
        assertNoIdentityDispatch("src/main/java/io/github/brooswitminecraft/dynamicvehicles/CarRenderer.java");
    }

    @Test
    void carEntityNeverDispatchesOnSpecIdentity() throws IOException {
        assertNoIdentityDispatch("src/main/java/io/github/brooswitminecraft/dynamicvehicles/CarEntity.java");
    }

    private static void assertNoIdentityDispatch(String relativePath) throws IOException {
        Path file = Path.of(relativePath);
        String source = Files.readString(file);
        String withoutComments = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll("")).replaceAll("");
        assertTrue(!IDENTITY_DISPATCH.matcher(withoutComments).find(),
                relativePath + " dispatches on spec identity (spec == VehicleSpec.X) outside a comment -- "
                        + "an override file for that car silently breaks this; dispatch on CarEntity#vehicleId() instead");
    }
}
