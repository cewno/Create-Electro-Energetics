package com.george_vi.electroenergetics.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 这是一个设备的类型类。
 * 设备是具有电力行为的方块的数据结构，即使在未加载时也能进行更新（tick）。
 * 设备使用独立的对象来存储数据。
 * @param <T> 数据持有者类的类型
 */
public abstract class SimulatedDevice<T> {
    final ResourceLocation id;
    public SimulatedDevice(ResourceLocation id) {
        this.id = id;
    }

    public ResourceLocation getID() {
        return id;
    }

    /**
     * 此方法在每次设备仿真之前调用一次。每个设备现在可以通过电阻或电压源来“桥接”其节点，从而实现节点的内部连接。
     * @param pos 设备方块在世界中的位置（此位置不一定总是已加载，在访问前请使用 {@link Level#isLoaded(BlockPos position)} 检查）
     * @param level 设备所在的维度
     * @param bridges 用于“桥接”节点或创建内部节点
     * @param extraData 设备本地保存的数据
     */
    public void preTick(BlockPos pos, Level level, BridgeCollector bridges, T extraData) {}

    /**
     * 此方法在每次设备仿真之后调用一次。每个设备现在可以处理电压、电流并在世界中显示结果。
     * @param pos 设备方块在世界中的位置（此位置不一定总是已加载，在访问前请使用 {@link Level#isLoaded(BlockPos position)} 检查）
     * @param level 设备所在的层级
     * @param results 用于存储结果电压的容器，还包含用于计算电流等有用方法
     * @param extraData 设备本地保存的数据
     */
    public void postTick(BlockPos pos, Level level, SimulationResults results, T extraData) {}

    /**
     * Reads the data for this device from an NBT format.
     * @param tag the serialized data
     * @return the data holder object for this device
     */
    public abstract T read(CompoundTag tag);


    /**
     * Writes the data for this device into an NBT format.
     * @param extraData the data holder object for this device
     * @return the serialized data
     */
    public abstract CompoundTag write(T extraData);

    /**
     * 在指定位置周围显示冒烟粒子效果。
     */
    protected void showOverheatingParticles(Level level, BlockPos pos) {
        if (!level.isLoaded(pos))
            return;
        Vec3 pPos = pos.getCenter();

        if (level.random.nextFloat() > 0.5f)
            ((ServerLevel)level).sendParticles(ParticleTypes.SMOKE, pPos.x, pPos.y, pPos.z, 5, 0.2, 0.2, 0.2, 0);
    }


    /**
     * 当热量 > 0 时，温度会上升，直到稳定在一个值。该值取决于热量值。
     * 这是温度稳定值的计算公式，其中 h 表示热量：
     * max(0, 30 * (h - 3.3))
     * 这与 WireType 中提到的温度类似，但数值不同。
     * 此处的温度不仅基于电流，还基于功率（例如使用 P=I²R 公式计算的热损耗）。
     *
     * @param temp 温度值（抽象单位）
     * @param heat 热量（能量损耗，单位为瓦特）
     * @return 新的温度值（抽象单位）
     */
    protected float updateTemp(float temp, float heat) {
        if (Float.isNaN(temp))
            temp = 0;

        float newTemp = heat + 30f;
        newTemp *= Math.min(temp < 0 ? 0 : 1 / (1 + (temp / 1000)), 1);
        newTemp = Math.max(temp - 33.3f + newTemp, 0);

        return newTemp;
    }


}
