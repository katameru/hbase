/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master.assignment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.MetaTableAccessor;
import org.apache.hadoop.hbase.backup.HFileArchiver;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.favored.FavoredNodesManager;
import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.hadoop.hbase.master.procedure.AbstractStateMachineRegionProcedure;
import org.apache.hadoop.hbase.master.procedure.MasterProcedureEnv;
import org.apache.hadoop.hbase.procedure2.ProcedureSuspendedException;
import org.apache.hadoop.hbase.procedure2.ProcedureYieldException;
import org.apache.hadoop.hbase.shaded.protobuf.generated.MasterProcedureProtos;
import org.apache.hadoop.hbase.shaded.protobuf.generated.MasterProcedureProtos.GCRegionState;

import com.google.common.collect.Lists;

/**
 * GC a Region that is no longer in use. It has been split or merged away.
 * Caller determines if it is GC time. This Procedure does not check.
 * <p>This is a Region StateMachine Procedure. We take a read lock on the Table and then
 * exclusive on the Region.
 */
@InterfaceAudience.Private
public class GCRegionProcedure extends AbstractStateMachineRegionProcedure<GCRegionState> {
  private static final Log LOG = LogFactory.getLog(GCRegionProcedure.class);

  public GCRegionProcedure(final MasterProcedureEnv env, final HRegionInfo hri) {
    super(env, hri);
  }

  public GCRegionProcedure() {
    // Required by the Procedure framework to create the procedure on replay
    super();
  }

  @Override
  public TableOperationType getTableOperationType() {
    return TableOperationType.REGION_GC;
  }

  @Override
  protected Flow executeFromState(MasterProcedureEnv env, GCRegionState state)
  throws ProcedureSuspendedException, ProcedureYieldException, InterruptedException {
    if (LOG.isTraceEnabled()) {
      LOG.trace(this + " execute state=" + state);
    }
    MasterServices masterServices = env.getMasterServices();
    try {
      switch (state) {
      case GC_REGION_PREPARE:
        // Nothing to do to prepare.
        setNextState(GCRegionState.GC_REGION_ARCHIVE);
        break;
      case GC_REGION_ARCHIVE:
        FileSystem fs = masterServices.getMasterFileSystem().getFileSystem();
        if (HFileArchiver.exists(masterServices.getConfiguration(), fs, getRegion())) {
          if (LOG.isDebugEnabled()) LOG.debug("Archiving region=" + getRegion().getShortNameToLog());
          HFileArchiver.archiveRegion(masterServices.getConfiguration(), fs, getRegion());
        }
        setNextState(GCRegionState.GC_REGION_PURGE_METADATA);
        break;
      case GC_REGION_PURGE_METADATA:
        // TODO: Purge metadata before removing from HDFS? This ordering is copied
        // from CatalogJanitor.
        AssignmentManager am = masterServices.getAssignmentManager();
        if (am != null) {
          if (am.getRegionStates() != null) {
            am.getRegionStates().deleteRegion(getRegion());
          }
        }
        MetaTableAccessor.deleteRegion(masterServices.getConnection(), getRegion());
        masterServices.getServerManager().removeRegion(getRegion());
        FavoredNodesManager fnm = masterServices.getFavoredNodesManager();
        if (fnm != null) {
          fnm.deleteFavoredNodesForRegions(Lists.newArrayList(getRegion()));
        }
        return Flow.NO_MORE_STATE;
      default:
        throw new UnsupportedOperationException(this + " unhandled state=" + state);
      }
    } catch (IOException ioe) {
      // TODO: This is going to spew log?
      LOG.warn("Error trying to GC " + getRegion().getShortNameToLog() + "; retrying...", ioe);
    }
    return Flow.HAS_MORE_STATE;
  }

  @Override
  protected void rollbackState(MasterProcedureEnv env, GCRegionState state) throws IOException, InterruptedException {
    // no-op
  }

  @Override
  protected GCRegionState getState(int stateId) {
    return GCRegionState.forNumber(stateId);
  }

  @Override
  protected int getStateId(GCRegionState state) {
    return state.getNumber();
  }

  @Override
  protected GCRegionState getInitialState() {
    return GCRegionState.GC_REGION_PREPARE;
  }

  @Override
  protected void serializeStateData(OutputStream stream) throws IOException {
    super.serializeStateData(stream);
    // Double serialization of regionname. Superclass is also serializing. Fix.
    final MasterProcedureProtos.GCRegionStateData.Builder msg =
        MasterProcedureProtos.GCRegionStateData.newBuilder()
        .setRegionInfo(HRegionInfo.convert(getRegion()));
    msg.build().writeDelimitedTo(stream);
  }

  @Override
  protected void deserializeStateData(InputStream stream) throws IOException {
    super.deserializeStateData(stream);
    final MasterProcedureProtos.GCRegionStateData msg =
        MasterProcedureProtos.GCRegionStateData.parseDelimitedFrom(stream);
    setRegion(HRegionInfo.convert(msg.getRegionInfo()));
  }

  @Override
  protected org.apache.hadoop.hbase.procedure2.Procedure.LockState acquireLock(MasterProcedureEnv env) {
    return super.acquireLock(env);
  }
}