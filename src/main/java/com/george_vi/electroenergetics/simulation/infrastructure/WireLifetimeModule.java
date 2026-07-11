package com.george_vi.electroenergetics.simulation.infrastructure;

import com.george_vi.electroenergetics.config.CEEConfigs;
import com.george_vi.electroenergetics.content.wire.SendPositionedWireParticlesPacket;
import com.george_vi.electroenergetics.content.wire.SendWireParticlesPacket;
import com.george_vi.electroenergetics.foundation.nodes.AttachedNode;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNode;
import com.george_vi.electroenergetics.foundation.nodes.InWorldNodeConnection;
import com.george_vi.electroenergetics.foundation.nodes.Node;
import com.george_vi.electroenergetics.simulation.SimulationResults;
import com.george_vi.electroenergetics.simulation.WireType;
import net.createmod.catnip.math.VecHelper;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

public class WireLifetimeModule {

    final InfrastructureSavedData sd;
    final ServerLevel level;
    final WireSimulationState wireSimulationState;

    public WireLifetimeModule(InfrastructureSavedData sd, ServerLevel level, WireSimulationState wireSimulationState) {
        this.sd = sd;
        this.level = level;
        this.wireSimulationState = wireSimulationState;
    }

    public void finishSimulation(SimulationResults results) {
        if (!CEEConfigs.server().wiresBreak.get())
            return;

        // 预取常量，避免多次重复计算
        final float MAX_TEMP_FACTOR = 0.85f;
        final float TEMP_DECAY = 33.3f;
        final float TEMP_DIVISOR = 1000f;
        final float RANDOM_THRESHOLD = 0.96f;

        InWorldNodeConnection longestWireToBreak = null;
        WireData longestWireDataToBreak = null;
        boolean isCatenary = false;

        // 使用增强 for 循环，避免迭代器额外开销
        for (Map.Entry<InWorldNodeConnection, ConnectionEntry> e : wireSimulationState.getAllConnections()) {
            InWorldNodeConnection connection = e.getKey();
            ConnectionEntry connData = e.getValue();
            WireData wireData = connData.wireData;          // 缓存，减少链式访问
            WireType wireType = wireData.wireType();
            List<WireSimulationState.CutWireEntry> cuts = connData.cuts;

            InWorldNode node1 = connection.node1();
            InWorldNode node2 = connection.node2();
            double wholeWireResistance = connData.resistance * wireData.length; // 缓存

            double current = 0.0;
            if (cuts == null || cuts.isEmpty()) {
                double vd = connData.getVoltageOnWire(results, node1, node2);
                current = vd / wholeWireResistance;
            } else {
                float prevPoint = 0f;
                Node prevNode = node1;
                // 为了减少方法调用，直接在循环内获取节点和距离
                for (WireSimulationState.CutWireEntry cut : cuts) {
                    float point = cut.point();
                    float dist = point - prevPoint;
                    if (dist < 0.01f)  // 直接用距离判断，避免冗余减法
                        continue;
                    AttachedNode node = cut.node();
                    double vd = Math.abs(results.getVoltageAt(prevNode, node));
                    // 计算当前段电流并更新最大值
                    double segCurrent = vd / (wholeWireResistance * dist);
                    if (segCurrent > current) {
                        current = segCurrent;
                    }
                    prevPoint = point;
                    prevNode = node;
                }
            }

            // ----- 温度更新优化 -----
            float temp = wireData.temperature;
            // 限制电流，避免溢出
            float cappedCurrent = (float) Math.min(current, 1000.0);
            // 计算温度缩放因子，避免多次 Math.min
            float factor;
            if (temp < 0f) {
                factor = 0f;
            } else {
                factor = 1f / (1f + temp / TEMP_DIVISOR);
                if (factor > 1f) factor = 1f;
            }
            float newTemp = temp - TEMP_DECAY + cappedCurrent * factor;
            if (newTemp < 0f) newTemp = 0f;
            wireData.temperature = newTemp;
            boolean increase = newTemp > temp;

            // ----- 粒子效果发送（条件提前） -----
            float maxTemp = (float) wireType.getMaxTemperature();
            if (newTemp > maxTemp * MAX_TEMP_FACTOR) {
                BlockPos nodePos1 = node1.sourcePos();
                // 先检查温度条件，再检查加载（加载检查较昂贵）
                if (level.isLoaded(nodePos1)) {
                    // 内部变量缓存
                    BlockPos nodePos2 = node2.sourcePos();
                    Vec3 center = VecHelper.lerp(0.5f, nodePos1.getCenter(), nodePos2.getCenter());
                    double distToCenter = nodePos1.getCenter().distanceTo(nodePos2.getCenter()) + 20.0;

                    if (connData.isCatenary) {
                        Vec3 pos1 = nodePos1.getBottomCenter();
                        Vec3 pos2 = nodePos2.getBottomCenter();
                        CatnipServices.NETWORK.sendToClientsAround(level, center, distToCenter,
                                new SendPositionedWireParticlesPacket(pos1, pos2, ParticleTypes.SMOKE, 0f, 0.2f));
                        Vec3 topPos1 = pos1.add(0, 1.5, 0);
                        Vec3 topPos2 = pos2.add(0, 1.5, 0);
                        float distance = (float) topPos1.distanceTo(topPos2);
                        CatnipServices.NETWORK.sendToClientsAround(level, center, distToCenter,
                                new SendPositionedWireParticlesPacket(topPos1, topPos2, ParticleTypes.SMOKE,
                                        350f * (0.05f / distance), 0.2f));
                    } else {
                        Vec3 pos1 = sd.getNodePosition(node1);
                        Vec3 pos2 = sd.getNodePosition(node2);
                        if (pos1 != null && pos2 != null) {
                            double distance = pos1.distanceTo(pos2);
                            CatnipServices.NETWORK.sendToClientsAround(level, center, distance + 20.0,
                                    new SendWireParticlesPacket(node1, node2, ParticleTypes.SMOKE,
                                            wireData.getSag(distance), 0.2f));
                        }
                    }
                }
            }

            // ----- 记录最长过热导线 -----
            if (newTemp > maxTemp && increase) {
                if (longestWireToBreak == null) {
                    longestWireToBreak = connection;
                    longestWireDataToBreak = wireData;
                    isCatenary = connData.isCatenary;
                } else if (longestWireDataToBreak.length < wireData.length) {
                    longestWireToBreak = connection;
                    longestWireDataToBreak = wireData;
                    isCatenary = connData.isCatenary;
                }
            }
        }

        // 最终断开或替换（仅一次，随机条件）
        if (longestWireToBreak != null && sd.level.random.nextFloat() > RANDOM_THRESHOLD) {
            // 缓存节点和位置，避免多次调用
            InWorldNode node1 = longestWireToBreak.node1();
            InWorldNode node2 = longestWireToBreak.node2();
            if (isCatenary) {
                sd.removeCatenary(node1.sourcePos(), node2.sourcePos());
                return;
            }
            WireType replaceWith = longestWireDataToBreak.wireType().overheatedReplacement();
            sd.removeConnection(longestWireToBreak); // 注意返回值未使用，但这里需要调用
            Vec3 pos1 = node1.getPosition(level);
            Vec3 pos2 = node2.getPosition(level);
            if (pos1 == null || pos2 == null)
                return;

            BlockPos centerPos = node1.sourcePos();
            double range = centerPos.getCenter().distanceTo(node2.sourcePos().getCenter()) + 20.0;
            Vec3 center = VecHelper.lerp(0.5f, centerPos.getCenter(), node2.sourcePos().getCenter());
            double sag = longestWireDataToBreak.getSag(pos1.distanceTo(pos2));

            if (replaceWith == null) {
                CatnipServices.NETWORK.sendToClientsAround(level, center, range,
                        new SendWireParticlesPacket(node1, node2, ParticleTypes.BUBBLE_POP, (float) sag, 4));
            } else {
                sd.connect(node1, node2, new WireData(replaceWith, longestWireDataToBreak.temperature(),
                        longestWireDataToBreak.attachments(), longestWireDataToBreak.length));
                CatnipServices.NETWORK.sendToClientsAround(level, center, range,
                        new SendWireParticlesPacket(node1, node2, ParticleTypes.LARGE_SMOKE, (float) sag, 4));
            }
        }
    }
}
