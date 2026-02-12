package com.george_vi.electroenergetics.simulation;

import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.foundation.nodes.Node;
import com.george_vi.electroenergetics.foundation.nodes.DirectionalNodeConnection;
import com.george_vi.electroenergetics.simulation.infrastructure.InfrastructureSavedData;
import com.george_vi.electroenergetics.simulation.simulator.ElectricalProperties;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;


public class SimulationResults {
    double[] voltages;
    Object2DoubleMap<DirectionalNodeConnection> sourceAmps;
    CircuitBuilder circuitBuilder;
    InfrastructureSavedData sd;
    final int microTicks;
    final int microTickBits;


    /**
     * 构造函数，用于初始化SimulationResults对象。
     *
     * @param voltages         电压数组，表示电路中各节点的电压值。
     * @param microTicks       微ticks数，用于模拟时间步长的细分。
     * @param microTickBits    微tick位数，用于表示微ticks的精度。
     * @param sourceAmps       源电流映射，存储方向性节点连接及其对应的电流值。
     * @param circuitBuilder   电路构建器，用于构建和管理电路结构。
     * @param sd               基础设施保存数据，包含电路的基础设施相关信息。
     */
    public SimulationResults(double[] voltages, int microTicks, int microTickBits, Object2DoubleMap<DirectionalNodeConnection> sourceAmps, CircuitBuilder circuitBuilder, InfrastructureSavedData sd) {
        this.voltages = voltages;
        this.sourceAmps = sourceAmps;
        this.circuitBuilder = circuitBuilder;
        this.sd = sd;
        this.microTicks = microTicks;
        this.microTickBits = microTickBits;
    }

    public InfrastructureSavedData getInfrastructure() {
        return sd;
    }


    /** 获取接线端子电压
     * @param node 接线端子
     * @return 电压
     */
    public double getVoltageAt(Node node) {
        int nodeID = circuitBuilder.nodeIndexes.getInt(node);
        if (nodeID == -1)
            return 0;
        if (microTickBits == 0)
            return voltages[nodeID];

        int i = nodeID << microTickBits;
        double rms = 0;
        for (int j = 0; j < microTicks; j++)
            rms += voltages[i|j] * voltages[i|j];
        rms /= microTicks;
        rms = Math.sqrt(rms);
        return rms;
    }

    /** 获取接线端子电压
     * @param pos 设备位置
     * @param id 接线端子ID
     * @return 电压
     */
    public double getVoltageAt(BlockPos pos, int id) {
        return getVoltageAt(new InWorldNode(id, pos));
    }

    public double getCurrentThrough(Node node1, Node node2) {
        DirectionalNodeConnection fc = connectionBetween(node1, node2);
        node1 = fc.node1();
        node2 = fc.node2();

        if (sourceAmps.containsKey(fc))
            return sourceAmps.getDouble(fc);
        if (sourceAmps.containsKey(fc.invert()))
            return -sourceAmps.getDouble(fc.invert());
        ElectricalProperties properties = circuitBuilder.getConnectionProperties(node1, node2);
        if (properties == null || properties.resistance() == 0)
            return 0;
        return getVoltageAt(node1, node2) / properties.resistance();
    }

    public double getCurrentThrough(BlockPos pos, int id1, int id2) {
        return getCurrentThrough(new InWorldNode(id1, pos), new InWorldNode(id2, pos));
    }
    public double getHeatLoss(BlockPos pos, int id1, int id2) {
        return getHeatLoss(new InWorldNode(id1, pos), new InWorldNode(id2, pos));
    }

    public double getHeatLoss(Node node1, Node node2) {
        double current = getCurrentThrough(node1, node2);
        ElectricalProperties properties = circuitBuilder.getConnectionProperties(node1, node2);
        if (current == 0 || properties == null || properties.resistance() == 0 || properties.currentSource() != 0 || properties.voltageSource() != 0)
            return 0;

        return current * current * properties.resistance();
    }

    DirectionalNodeConnection connectionBetween(Node node1, Node node2) {

        // the reason this is here, is when a device creates a non-ideal voltage source, it adds a node in the middle.
        // this just returns the real connection, so that the device doesn't have to worry about these nodes.
        int nodeId1 = circuitBuilder.nodeIndexes.getInt(node1);
        int nodeId2 = circuitBuilder.nodeIndexes.getInt(node2);
        if (nodeId1 == -1 || nodeId2 == -1)
            return new DirectionalNodeConnection(node1, node2);

        WrappedIndexedNode indexedNode1 = circuitBuilder.getNode(nodeId1);
        WrappedIndexedNode indexedNode2 = circuitBuilder.getNode(nodeId2);
        if (indexedNode1.adjacency.containsKey(indexedNode2.ordinal))
            return new DirectionalNodeConnection(node1, node2);

        IntSet node1connections = indexedNode1.adjacency.keySet();
        IntSet node2connections = indexedNode2.adjacency.keySet();

        List<Node> nodesInTheMiddle = new ArrayList<>();
        for (int node1connection : node1connections) {
            for (int node2connection : node2connections) {
                if (node1connection == node2connection)
                    nodesInTheMiddle.add(circuitBuilder.allIndexedNodes.get(node1connection).node);
            }
        }
        if (nodesInTheMiddle.size() == 1)
            return new DirectionalNodeConnection(node1, nodesInTheMiddle.getFirst());
        return new DirectionalNodeConnection(node1, node2);
    }

    public double getVoltageAt(BlockPos pos, int n1, int n2) {
        return getVoltageAt(new InWorldNode(n1, pos), new InWorldNode(n2, pos));
    }

    /**
     * 计算两个节点之间的电压。
     *
     * @param n1 第一个节点对象
     * @param n2 第二个节点对象
     * @return 返回两个节点之间的电压值。如果任一节点未找到，则返回0；
     *         如果启用了微时间片（microTickBits > 0），则返回均方根电压值；
     *         否则返回瞬时电压差值。
     */
    public double getVoltageAt(Node n1, Node n2) {
        // 获取两个节点在电路中的索引
        int nodeId1 = circuitBuilder.nodeIndexes.getInt(n1);
        int nodeId2 = circuitBuilder.nodeIndexes.getInt(n2);
        if (nodeId1 == -1 || nodeId2 == -1)
            return 0;
        int id1 = nodeId1 << microTickBits;
        int id2 = nodeId2 << microTickBits;
        if (microTickBits == 0)
            return voltages[id1] - voltages[id2];
        double rms = 0;
        for (int j = 0; j < microTicks; j++)
            rms += (voltages[id1|j] - voltages[id2|j]) * (voltages[id1|j] - voltages[id2|j]);
        rms /= microTicks;
        rms = Math.sqrt(rms);
        return rms;
    }

    public double[] getVoltages(Node n1) {
        int nodeID = circuitBuilder.nodeIndexes.getInt(n1);
        if (nodeID == -1)
            return new double[0];
        int id = nodeID << microTickBits;
        double[] r = new double[microTicks];
        for (int j = 0; j < microTicks; j++)
            r[j] = voltages[id|j];
        return r;
    }
}
