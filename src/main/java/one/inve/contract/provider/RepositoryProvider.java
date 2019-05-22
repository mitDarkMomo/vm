package one.inve.contract.provider;

import one.inve.contract.ethplugin.core.Repository;
import one.inve.contract.inve.INVERepositoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;

/**
 * 底层数据库提供类：根据需要选择不同的底层实现方案
 * @author 肖毅
 * @since 2019-01-15
 */
public class RepositoryProvider {
    private static final Logger logger = LoggerFactory.getLogger("RepositoryProvider");

    private static HashMap<String, Repository> TRACK;

    public static Repository getTrack(String dbId) {
        if(TRACK == null) {
            TRACK = new HashMap<>();
        }

        Repository track = TRACK.get(dbId);
        if(track == null) {
            track = INVERepositoryManager.getRepoRoot(dbId);
            TRACK.put(dbId, track);
        }
        return track;
    }

    /**
     * 根据指定 root.cfg 文件路径以及 state 数据库路径来获取数据库操作类
     * @param cfgDir root.cfg 的完整路径
     * @param dbPath rocksDB 数据库的父路径
     * @param dbId 数据库前缀
     * @return
     */
    public static Repository getTrackByPath(String cfgDir, String dbPath, String dbId) {
        if(TRACK == null) {
            TRACK = new HashMap<>();
        }

        Repository track = TRACK.get(dbId);
        if(track == null) {
            track = INVERepositoryManager.getRepoRoot(cfgDir, dbPath, dbId);
            TRACK.put(dbId, track);
        }
        return track;
    }
}