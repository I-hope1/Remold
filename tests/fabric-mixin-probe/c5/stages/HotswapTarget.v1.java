package com.example.examplemod;

import remold.annotation.OnReload;

/** C5 hot-swap target: plain mod class loaded by Forge's TransformingClassLoader. */
public class HotswapTarget {
    public int value = 1;

    public HotswapTarget() {
    }

    @OnReload
    public void onReload() {
        System.out.println("[C5-READ] value=" + value);
    }
}
