package com.george_vi.electroenergetics.foundation.nodes;

/**
 * 描述一个未附着在方块上的节点，即“自由”节点。
 * 您可以创建这些节点并将其用于仿真。
 * @see InWorldNode
 */
public class AttachedNode extends Node {
    public final String ownerID;
    public final int id;

    public AttachedNode(int id, String ownerID) {
        this.ownerID = ownerID;
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AttachedNode that = (AttachedNode) o;
        return id == that.id && ownerID.equals(that.ownerID);
    }

    @Override
    public int hashCode() {
        int result = id;
        result = 31 * result + ownerID.hashCode();
        result ^= (result >>> 16);
        return result;
    }

    @Override
    public String toString() {
        return "N" + id + "@" + ownerID;
    }
}
