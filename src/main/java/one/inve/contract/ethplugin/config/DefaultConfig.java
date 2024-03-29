/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package one.inve.contract.ethplugin.config;

import one.inve.contract.ethplugin.datasource.Source;
import one.inve.contract.ethplugin.db.BlockStore;
import one.inve.contract.ethplugin.db.IndexedBlockStore;
import one.inve.contract.ethplugin.db.PruneManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

import static java.util.Arrays.asList;

/**
 *
 * @author Roman Mandeleil
 * Created on: 27/01/2015 01:05
 */
@Configuration
@Import(CommonConfig.class)
public class DefaultConfig {
    private static Logger logger = LoggerFactory.getLogger("general");

    @Autowired
    ApplicationContext appCtx;

    @Autowired
    CommonConfig commonConfig;

    @Autowired
    SystemProperties config;

    private final static List<Class<? extends Exception>> FATAL_EXCEPTIONS = asList(FatalBeanException.class);

    public DefaultConfig() {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            logger.error("Uncaught exception", e);
            FATAL_EXCEPTIONS.stream()
                    .filter(errType -> errType.isInstance(e))
                    .findFirst()
                    .ifPresent(errType -> System.exit(1));
        });
    }

    @Bean
    public BlockStore blockStore(){
        IndexedBlockStore indexedBlockStore = new IndexedBlockStore();
        Source<byte[], byte[]> block = commonConfig.cachedDbSource("block");
        Source<byte[], byte[]> index = commonConfig.cachedDbSource("index");
        indexedBlockStore.init(index, block);

        return indexedBlockStore;
    }

    @Bean
    public PruneManager pruneManager() {
        if (config.databasePruneDepth() >= 0) {
            return new PruneManager((IndexedBlockStore) blockStore(), commonConfig.stateSource().getJournalSource(),
                    commonConfig.stateSource().getNoJournalSource(), config.databasePruneDepth());
        } else {
            return new PruneManager(null, null, null, -1); // dummy
        }
    }
}
