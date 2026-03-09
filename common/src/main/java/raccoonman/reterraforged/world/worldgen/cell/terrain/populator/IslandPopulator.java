package raccoonman.reterraforged.world.worldgen.cell.terrain.populator;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.CellPopulator;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Terrain;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.function.Interpolation;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;

public class IslandPopulator implements CellPopulator {
	private CellPopulator ocean;
    private IslandType upper;
	private Interpolation interpolation;
    private float blendLower;
    private float blendUpper;
    private float blendRange;
    private float seaLevel;
    private Noise islandThresholdNoise;
    private Noise islandChanceVarianceNoise;
    
    public IslandPopulator(Levels levels, CellPopulator ocean, float min, float max) {
        this(levels, ocean, min, max, Interpolation.LINEAR);
    }
    
    public IslandPopulator(Levels levels, CellPopulator ocean, float min, float max, Interpolation interpolation) {
        this.ocean = ocean;
        this.interpolation = interpolation;
        this.blendLower = min;
        this.blendUpper = max;
        this.blendRange = this.blendUpper - this.blendLower;
        this.upper = upperPopulator(levels, this.blendRange, 25);
        this.seaLevel = levels.water;

        Noise islandThresholdNoise = Noises.simplex(3526, 1200, 1);
        islandThresholdNoise = Noises.warpPerlin(islandThresholdNoise, 3526, 1200, 3, 600);
        islandThresholdNoise = Noises.clamp(islandThresholdNoise, 0.0F, 1.0F);
        islandThresholdNoise = Noises.map(islandThresholdNoise, 0.7F, 0.8F);
        this.islandThresholdNoise = islandThresholdNoise;

        Noise islandChanceVarianceNoise = Noises.simplex(54326, 1, 3);
        islandChanceVarianceNoise = Noises.clamp(islandChanceVarianceNoise, 0.0F, 1.0F);
        islandChanceVarianceNoise = Noises.map(islandChanceVarianceNoise, 0.1F, 0.3F);
        this.islandChanceVarianceNoise = islandChanceVarianceNoise;
    }
    
    @Override
    public void apply(Cell cell, float x, float z) {
        // [Fix 問題2] 大陸セル（湾含む）は ocean として扱う
        // 真の海洋セル（スキップされたVoronoiセル）のみ continentEdge == 0.0F
        if (cell.continentEdge > 0.0F) {
            this.ocean.apply(cell, x, z);
            return;
        }

    	float islandThresholdMin = this.islandThresholdNoise.compute(x, z, 0);
    	float islandThresholdMax = islandThresholdMin + 4.0F;

    	float regionVarianceAlpha = cell.terrainRegionId > this.islandChanceVarianceNoise.compute(cell.continentX, cell.continentZ, 0) ? 0.0F : 1.0F;
    	float regionEdgeAlpha = NoiseUtil.clamp(cell.terrainRegionEdge, islandThresholdMin, islandThresholdMax);
    	regionEdgeAlpha = NoiseUtil.map(regionEdgeAlpha, 0.0F, 1.0F, 2.0F);
    	
    	float rawAlpha = cell.continentDistance * regionVarianceAlpha * regionEdgeAlpha;
        float islandAlpha = this.blendUpper - rawAlpha;   // 反転: 島の内陸ほど小さい値
        if (islandAlpha >= this.blendUpper) {  // rawAlpha ≤ 0 → ocean
            this.ocean.apply(cell, x, z);
            return;
        }
        if (islandAlpha < this.blendLower) {   // rawAlpha ≥ blendRange → inland
            this.upper.apply(cell, x, z, rawAlpha);
            return;
        }
        // ブレンドゾーン: 海面下なら ocean の terrain を維持
        // alpha = rawAlpha / blendRange (0=coast, 1=inland)
        float alpha = this.interpolation.apply((this.blendUpper - islandAlpha) / this.blendRange);
        this.ocean.apply(cell, x, z);
        float lowerHeight = cell.height;
        Terrain oceanTerrain = cell.terrain;
        this.upper.apply(cell, x, z, rawAlpha);
        float upperHeight = cell.height;
        float blendedHeight = NoiseUtil.lerp(lowerHeight, upperHeight, alpha);
        cell.height = blendedHeight;
        if (blendedHeight < this.seaLevel) {
            cell.terrain = oceanTerrain;
        }
    }
    
    private static IslandType upperPopulator(Levels levels, float blendRange, int maxHeight) {
        float islandMin = levels.water(5);
        float islandMax = levels.water(maxHeight);
        return (cell, x, z, rawAlpha) -> {
            cell.terrain = TerrainType.MUSHROOM_FIELDS;
            // 島の中心（rawAlpha 大）ほど高い地形
            float alpha = NoiseUtil.clamp((rawAlpha - blendRange) / (blendRange * 1.5F), 0.0F, 1.0F);
            cell.height = NoiseUtil.lerp(islandMin, islandMax, alpha);
        };
    }

    public interface IslandType {
    	void apply(Cell cell, float x, float z, float rawAlpha);
    }
}
