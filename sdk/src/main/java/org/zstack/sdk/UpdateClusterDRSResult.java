package org.zstack.sdk;

import org.zstack.sdk.ClusterDRSInventory;

public class UpdateClusterDRSResult {
    public ClusterDRSInventory inventory;
    public void setInventory(ClusterDRSInventory inventory) {
        this.inventory = inventory;
    }
    public ClusterDRSInventory getInventory() {
        return this.inventory;
    }

}
