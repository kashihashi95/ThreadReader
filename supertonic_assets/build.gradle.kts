plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("supertonic_assets")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
