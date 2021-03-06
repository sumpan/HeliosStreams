package com.heliosapm.metrichub.speedment.tsdb.public_.tsd_lastsync.generated;

import com.heliosapm.metrichub.speedment.tsdb.public_.tsd_lastsync.TsdLastsync;
import com.speedment.runtime.config.identifier.TableIdentifier;
import com.speedment.runtime.core.manager.AbstractManager;
import com.speedment.runtime.field.Field;
import java.util.stream.Stream;
import javax.annotation.Generated;

/**
 * The generated base implementation for the manager of every {@link
 * com.heliosapm.metrichub.speedment.tsdb.public_.tsd_lastsync.TsdLastsync}
 * entity.
 * <p>
 * This file has been automatically generated by Speedment. Any changes made to
 * it will be overwritten.
 * 
 * @author Speedment
 */
@Generated("Speedment")
public abstract class GeneratedTsdLastsyncManagerImpl extends AbstractManager<TsdLastsync> implements GeneratedTsdLastsyncManager {
    
    private final TableIdentifier<TsdLastsync> tableIdentifier;
    
    protected GeneratedTsdLastsyncManagerImpl() {
        this.tableIdentifier = TableIdentifier.of("tsdb", "public", "tsd_lastsync");
    }
    
    @Override
    public TableIdentifier<TsdLastsync> getTableIdentifier() {
        return tableIdentifier;
    }
    
    @Override
    public Stream<Field<TsdLastsync>> fields() {
        return Stream.of(
            TsdLastsync.TABLE_NAME,
            TsdLastsync.ORDERING,
            TsdLastsync.LAST_SYNC
        );
    }
    
    @Override
    public Stream<Field<TsdLastsync>> primaryKeyFields() {
        return Stream.of(
            TsdLastsync.TABLE_NAME
        );
    }
}