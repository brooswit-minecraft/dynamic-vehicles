package io.github.brooswitminecraft.dynamicvehicles;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ForceApplicationReceiptsTest {
 @Test void combinedThenSplitCannotRetainCombinedReceipt() { var r=new ForceApplicationReceipts(1); r.publish(0,true,1,2,2); long old=r.sequence(0); r.reset(); r.publish(0,false,3,4,5); assertFalse(r.combined(0)); assertTrue(r.valid(0)); assertTrue(r.sequence(0)>old); assertEquals(5,r.lateralY(0)); }
 @Test void contactThenNoContactInvalidatesHeightsAtNewSequence() { var r=new ForceApplicationReceipts(1); r.publish(0,true,1,2,2); long old=r.sequence(0); r.reset(); assertFalse(r.valid(0)); assertFalse(r.combined(0)); assertTrue(r.sequence(0)>old); assertTrue(Double.isNaN(r.contactY(0))); assertTrue(Double.isNaN(r.tireY(0))); assertTrue(Double.isNaN(r.lateralY(0))); }
 @Test void splitThenCombinedReplacesCurrentBranchAndGeometry() { var r=new ForceApplicationReceipts(1); r.publish(0,false,1,2,3); r.reset(); r.publish(0,true,4,5,5); assertTrue(r.valid(0)); assertTrue(r.combined(0)); assertEquals(4,r.contactY(0)); assertEquals(5,r.tireY(0)); assertEquals(5,r.lateralY(0)); }
 @Test void subsequentResetRejectsStaleCombinedGeometry() { var r=new ForceApplicationReceipts(1); r.publish(0,true,1,2,2); long old=r.sequence(0); r.reset(); assertTrue(r.sequence(0)>old); assertFalse(r.valid(0)); assertFalse(r.combined(0)); assertTrue(Double.isNaN(r.contactY(0))); assertTrue(Double.isNaN(r.tireY(0))); assertTrue(Double.isNaN(r.lateralY(0))); }
}
