package com.mediatek.systemui.ext;

/**
 * M: This enum defines the type of behavior set for different operator.
 */
public enum BehaviorSet {

    DEFAULT_BS(0), OP01_BS(1), OP02_BS(2), OP09_BS(3);

    private int mBehaviorSet;

    private BehaviorSet(int behaviorSet) {
        mBehaviorSet = behaviorSet;
    }

    public int getBehaviorSet() {
        return mBehaviorSet;
    }

}
