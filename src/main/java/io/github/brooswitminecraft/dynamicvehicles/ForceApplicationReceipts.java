package io.github.brooswitminecraft.dynamicvehicles;

import java.util.Arrays;

/** Current-substep-only wheel force receipts; used by the production Sable tick. */
final class ForceApplicationReceipts {
    private final boolean[] valid;
    private final boolean[] combined;
    private final long[] sequence;
    private final double[] contactY, tireY, lateralY;
    private long current;
    ForceApplicationReceipts(int wheels) { valid=new boolean[wheels]; combined=new boolean[wheels]; sequence=new long[wheels]; contactY=new double[wheels]; tireY=new double[wheels]; lateralY=new double[wheels]; reset(); }
    void reset() { current++; Arrays.fill(valid,false); Arrays.fill(combined,false); Arrays.fill(sequence,current); Arrays.fill(contactY,Double.NaN); Arrays.fill(tireY,Double.NaN); Arrays.fill(lateralY,Double.NaN); }
    void publish(int wheel, boolean isCombined, double contact, double tire, double lateral) { valid[wheel]=true; combined[wheel]=isCombined; sequence[wheel]=current; contactY[wheel]=contact; tireY[wheel]=tire; lateralY[wheel]=lateral; }
    boolean valid(int w){return valid[w];} boolean combined(int w){return combined[w];} long sequence(int w){return sequence[w];} double contactY(int w){return contactY[w];} double tireY(int w){return tireY[w];} double lateralY(int w){return lateralY[w];}
}
