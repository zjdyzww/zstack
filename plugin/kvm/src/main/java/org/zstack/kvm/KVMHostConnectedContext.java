package org.zstack.kvm;

/**
 */
public class KVMHostConnectedContext {
    private KVMHostInventory inventory;
    private boolean newAddedHost;
    private String skipPackages;

    public boolean isNewAddedHost() {
        return newAddedHost;
    }

    public void setNewAddedHost(boolean newAddedHost) {
        this.newAddedHost = newAddedHost;
    }

    public KVMHostInventory getInventory() {
        return inventory;
    }

    public void setInventory(KVMHostInventory inventory) {
        this.inventory = inventory;
    }

    public String getSkipPackages() {
        return skipPackages;
    }

    public void setSkipPackages(String skipPackages) {
        this.skipPackages = skipPackages;
    }
}
