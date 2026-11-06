package com.example.examplemod;

import remold.annotation.OnReload;

/** C5 stage 1: method added by a real source edit. */
public class HotswapTarget {
    public int value = 1;

    public HotswapTarget() {
    }

    public String c5Method() {
        return "C5-V1";
    }

    @OnReload
    public void onReload() {
        System.out.println("[C5-READ] stage1 method=" + c5Method() + " value=" + value);
    }
}
