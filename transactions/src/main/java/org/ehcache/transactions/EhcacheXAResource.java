/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ehcache.transactions;

import org.ehcache.exceptions.CacheAccessException;
import org.ehcache.spi.cache.Store;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * @author Ludovic Orban
 */
public class EhcacheXAResource<K, V> implements XAResource {

  private final Store<K, SoftLock<V>> underlyingStore;
  private final XaTransactionStateStore stateStore;
  private final XATransactionContextFactory<K, V> transactionContextFactory;
  private volatile Xid currentXid;

  public EhcacheXAResource(Store<K, SoftLock<V>> underlyingStore, XaTransactionStateStore stateStore, XATransactionContextFactory<K, V> transactionContextFactory) {
    this.underlyingStore = underlyingStore;
    this.stateStore = stateStore;
    this.transactionContextFactory = transactionContextFactory;
  }

  @Override
  public void commit(Xid xid, boolean onePhase) throws XAException {
    TransactionId transactionId = new TransactionId(xid);
    try {
      XATransactionContext<K, V> transactionContext = transactionContextFactory.get(transactionId);
      if (transactionContext == null) {
        throw new EhcacheXAException("Cannot commit unknown XID : " + xid, XAException.XAER_PROTO);
      }

      try {
        if (onePhase) {
          transactionContext.commitInOnePhase();
        } else {
          transactionContext.commit();
        }
      } catch (IllegalStateException ise) {
        throw new EhcacheXAException("Cannot commit XID : " + xid, XAException.XAER_PROTO, ise);
      } catch (CacheAccessException cae) {
        throw new EhcacheXAException("Cannot commit XID : " + xid, XAException.XAER_RMERR, cae);
      }
    } finally {
      transactionContextFactory.destroy(transactionId);
    }
  }

  @Override
  public void forget(Xid xid) throws XAException {
    TransactionId transactionId = new TransactionId(xid);
    XAState xaState = stateStore.getState(transactionId);
    if (xaState == null) {
      throw new EhcacheXAException("Cannot forget unknown XID : " + xid, XAException.XAER_PROTO);
    }
    if (xaState == XAState.IN_DOUBT) {
      throw new EhcacheXAException("Cannot forget in-doubt XID : " + xid, XAException.XAER_PROTO);
    }
    stateStore.forgetHeuristicDecision(transactionId);
  }

  @Override
  public int getTransactionTimeout() throws XAException {
    return 0;
  }

  @Override
  public boolean isSameRM(XAResource xaResource) throws XAException {
    return xaResource == this;
  }

  @Override
  public int prepare(Xid xid) throws XAException {
    XATransactionContext<K, V> transactionContext = transactionContextFactory.get(new TransactionId(xid));
    if (transactionContext == null) {
      throw new EhcacheXAException("Cannot prepare unknown XID : " + xid, XAException.XAER_PROTO);
    }

    try {
      return (transactionContext.prepare() > 0) ? XA_OK : XA_RDONLY;
    } catch (IllegalStateException ise) {
      throw new EhcacheXAException("Cannot prepare XID : " + xid, XAException.XAER_PROTO, ise);
    } catch (CacheAccessException cae) {
      throw new EhcacheXAException("Cannot prepare XID : " + xid, XAException.XAER_RMERR, cae);
    }
  }

  @Override
  public Xid[] recover(int flag) throws XAException {
    if ((flag & XAResource.TMSTARTRSCAN) == XAResource.TMSTARTRSCAN) {
      List<Xid> xids = new ArrayList<Xid>();
      Set<TransactionId> transactionIds = stateStore.recover().keySet();
      for (TransactionId transactionId : transactionIds) {
        if (!transactionContextFactory.contains(transactionId)) {
          xids.add(transactionId.getSerializableXid());
        }
      }
      return xids.toArray(new Xid[xids.size()]);
    }
    return new Xid[0];
  }

  @Override
  public void rollback(Xid xid) throws XAException {
    TransactionId transactionId = new TransactionId(xid);
    try {
      XATransactionContext<K, V> transactionContext = transactionContextFactory.get(transactionId);
      if (transactionContext == null) {
        throw new EhcacheXAException("Cannot rollback unknown XID : " + xid, XAException.XAER_PROTO);
      }
      transactionContext.rollback();
    } catch (IllegalStateException ise) {
      throw new EhcacheXAException("Cannot rollback XID : " + xid, XAException.XAER_PROTO, ise);
    } catch (CacheAccessException cae) {
      throw new EhcacheXAException("Cannot rollback XID : " + xid, XAException.XAER_RMERR, cae);
    } finally {
      transactionContextFactory.destroy(transactionId);
    }
  }

  @Override
  public boolean setTransactionTimeout(int seconds) throws XAException {
    return false;
  }

  @Override
  public void start(Xid xid, int flag) throws XAException {
    if (flag != XAResource.TMNOFLAGS) {
      throw new EhcacheXAException("Start flag not supported : " + xlat(flag), XAException.XAER_PROTO);
    }
    if (currentXid != null) {
      throw new EhcacheXAException("Already started on : " + xid, XAException.XAER_PROTO);
    }

    currentXid = xid;
    TransactionId transactionId = new TransactionId(currentXid);
    if (!transactionContextFactory.contains(transactionId)) {
      transactionContextFactory.create(transactionId, underlyingStore, stateStore);
    }
  }

  @Override
  public void end(Xid xid, int flag) throws XAException {
    if (flag != XAResource.TMNOFLAGS) {
      throw new EhcacheXAException("End flag not supported : " + xlat(flag), XAException.XAER_PROTO);
    }
    if (currentXid == null) {
      throw new EhcacheXAException("Not started on : " + xid, XAException.XAER_PROTO);
    }

    currentXid = null;
  }

  private static String xlat(int flags) {
    StringBuilder sb = new StringBuilder();

    if ((flags & XAResource.TMNOFLAGS) == XAResource.TMNOFLAGS) {
      sb.append("TMNOFLAGS|");
    }
    if ((flags & XAResource.TMSUCCESS) == XAResource.TMSUCCESS) {
      sb.append("TMSUCCESS|");
    }
    if ((flags & XAResource.TMFAIL) == XAResource.TMFAIL) {
      sb.append("TMFAIL|");
    }
    if ((flags & XAResource.TMJOIN) == XAResource.TMJOIN) {
      sb.append("TMJOIN|");
    }
    if ((flags & XAResource.TMONEPHASE) == XAResource.TMONEPHASE) {
      sb.append("TMONEPHASE|");
    }
    if ((flags & XAResource.TMSUSPEND) == XAResource.TMSUSPEND) {
      sb.append("TMRESUME|");
    }
    if ((flags & XAResource.TMRESUME) == XAResource.TMRESUME) {
      sb.append("TMRESUME|");
    }
    if ((flags & XAResource.TMSTARTRSCAN) == XAResource.TMSTARTRSCAN) {
      sb.append("TMSTARTRSCAN|");
    }
    if ((flags & XAResource.TMENDRSCAN) == XAResource.TMENDRSCAN) {
      sb.append("TMSTARTRSCAN|");
    }

    if (sb.length() > 0) {
      sb.deleteCharAt(sb.length() - 1);
    }

    return sb.toString();
  }

  public XATransactionContext<K, V> getCurrentContext() throws CacheAccessException {
    if (currentXid == null) {
      throw new CacheAccessException("Cannot get current context when XAResource.start() was not called");
    }
    return transactionContextFactory.get(new TransactionId(currentXid));
  }
}
