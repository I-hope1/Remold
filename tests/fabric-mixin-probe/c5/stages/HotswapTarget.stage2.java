package com.example.examplemod;

import remold.annotation.OnReload;

/** C5 stage 2: field added by a real source edit; InitFix must patch the live instance. */
public class HotswapTarget {
    public int value = 1;
    public int addedField = 456;

    public HotswapTarget() {
    }

    public String c5Method() {
        return "C5-V1";
    }

    @OnReload
    public void onReload() {
        System.out.println("[C5-READ] stage2 method=" + c5Method() + " added=" + addedField + " value=" + value);
    }
}
