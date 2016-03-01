/*
 * Copyright (c) 1998-2015 Caucho Technology -- all rights reserved
 *
 * This file is part of Baratine(TM)
 *
 * Each copy or derived work must preserve the copyright notice and this
 * notice unmodified.
 *
 * Baratine is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Baratine is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE, or any warranty
 * of NON-INFRINGEMENT.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Baratine; if not, write to the
 *
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package com.caucho.v5.amp.message;

import io.baratine.service.Result;
import io.baratine.service.ServiceExceptionClosed;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.caucho.v5.amp.ServiceRefAmp;
import com.caucho.v5.amp.queue.WorkerDeliver;
import com.caucho.v5.amp.spi.ActorAmp;
import com.caucho.v5.amp.spi.HeadersAmp;
import com.caucho.v5.amp.spi.InboxAmp;
import com.caucho.v5.amp.spi.MethodAmp;
import com.caucho.v5.amp.spi.OutboxAmp;
import com.caucho.v5.util.L10N;

/**
 * Handles the context for an actor, primarily including its
 * query map.
 */
public class QueryMessageBase<T> extends MethodMessageBase
  implements Result<T>
{
  private static final Logger log = Logger.getLogger(QueryMessageBase.class.getName());
  private static final L10N L = new L10N(QueryMessageBase.class);
  
  private final long _timeout;
  private final InboxAmp _inboxCaller;
  
  private State _state = State.QUERY;
  
  private Object _value;
  private Throwable _replyError;
  
  /*
  protected QueryMessageBase(ServiceRefAmp serviceRef,
                             MethodAmp method,
                             long expireTime)
  {
    super(serviceRef, method);
    
    _inboxCaller = getOutboxCaller().getInbox();
    Objects.requireNonNull(_inboxCaller);
    
    _timeout = expireTime;
  }
  */
  
  protected QueryMessageBase(OutboxAmp outboxCaller,
                             ServiceRefAmp serviceRef,
                             MethodAmp method,
                             long expireTime)
  {
    super(outboxCaller, serviceRef, method);

    _inboxCaller = outboxCaller.inbox();
    Objects.requireNonNull(_inboxCaller);
    
    _timeout = expireTime;
  }
  
  protected QueryMessageBase(OutboxAmp outboxCaller,
                             HeadersAmp headers,
                             ServiceRefAmp serviceRef,
                             MethodAmp method,
                             long expireTime)
  {
    super(outboxCaller, headers, serviceRef, method);

    _inboxCaller = outboxCaller.inbox();
    Objects.requireNonNull(_inboxCaller);
    
    _timeout = expireTime;
  }
  
  /**
   * Remote proxy constructor, which sets the inbox caller to an inbox 
   * accessible to remote replies.
   */
  public QueryMessageBase(OutboxAmp outboxCaller,
                          InboxAmp inboxCaller,
                          HeadersAmp headersCaller,
                          ServiceRefAmp serviceRef,
                          MethodAmp method,
                          long expireTime)
  {
    super(outboxCaller, headersCaller, serviceRef, method);

    Objects.requireNonNull(outboxCaller);
    
    _inboxCaller = inboxCaller;
    Objects.requireNonNull(inboxCaller);
    
    _timeout = expireTime;
  }
  
  protected InboxAmp getInboxCaller()
  {
    return _inboxCaller;
  }

  /*
  @Override
  public ActorAmp getActorInvoke(ActorAmp actorDeliver)
  {
    return actorDeliver;
  }
  */
  
  @Override
  public HeadersAmp getHeaders()
  {
    if (isReply()) {
      return getHeadersCaller();
    }
    else {
      return super.getHeaders();
    }
  }
  
  protected final State getState()
  {
    return _state;
  }
  
  @Override
  public boolean isFuture()
  {
    return false;
  }
  
  protected final Object getReply()
  {
    return _value;
  }
  
  protected final Throwable getException()
  {
    return _replyError;
  }
    
  protected final long getTimeout()
  {
    return _timeout;
  }

  // final
  @Override
  public void ok(T value)
  {
    _state.complete(this, value);
  }

  @Override
  public final void fail(Throwable exn)
  {
    _state.fail(this, exn);
  }
  
  @Override
  public final void handle(T value, Throwable exn)
  {
    if (exn != null) {
      fail(exn);
    }
    else {
      ok(value);
    }
  }

  @Override
  public final void offer(long timeout)
  {
    _state.offer(this, timeout);
  }

  protected void offerQuery(long timeout)
  {
    OutboxAmp outbox = getOutboxCaller();
      
    //_callerInbox = outbox.getInbox();
    outbox.offer(this);
  }
  
  public void toSent()
  {
    _state = _state.toSent();
  }

  protected void offerResult(long timeout)
  {
    if (isFuture()) {
      // short-circuit for futures
      invoke(null, null);
      //invokeReply(null);
      return;
    }
    
    try (OutboxAmp outbox = OutboxAmp.currentOrCreate(getServiceRef().manager())) {
      outbox.offer(this);
    }
  }
  
  protected final void sendReplyAsync(Result<T> result)
  {
    _state = State.CLOSED;
    
    Throwable exn = getException();
    
    if (exn != null) {
      result.fail(exn);
    }
    else {
      result.completeFuture(this, (T) getReply());
    }
  }

  @Override
  public final void offerQueue(long timeout)
  {
    _state.offerQueue(this, timeout);
  }
  
  @Override
  public final WorkerDeliver getWorker()
  {
    return _state.getWorker(this);
  }
  
  //
  // invoke completed on receiving end
  //
  
  protected final boolean isReply()
  {
    return _state.isReply();
  }

  @Override
  public final void invoke(InboxAmp inbox, ActorAmp actorDeliver)
  {
    _state.invoke(this, inbox, actorDeliver);
    /*
    if (isReply()) {
      try {
        invokeReply(actorDeliver);
      } catch (Throwable e) {
        if (log.isLoggable(Level.FINER)) {
          log.log(Level.FINER, e.toString(), e);
        }
      }
    }
    else {
      try {
        invokeQuery(inbox, actorDeliver);
      } catch (Throwable e) {
        fail(e);
      }
    }
    */
  }
  
  public void invokeQuery(InboxAmp inbox, ActorAmp actorDeliver)
  {
    RuntimeException e = new UnsupportedOperationException(getClass().getName());
    e.fillInStackTrace();

    fail(e);
  }
  
  protected boolean invokeComplete(ActorAmp actorDeliver)
  {
    return true;
  }
  
  protected boolean invokeFail(ActorAmp actorDeliver)
  {
    return true;
  }
  
  /*
  protected final void invokeReply(ActorAmp actor)
  {
    switch (_state) {
    case COMPLETE:
      onCompleted(_replyHeaders, actor, _value);
      break;
      
    case FAILED:
      onFailed(_replyHeaders, actor, _replyError);
      break;
      
    default:
      throw new IllegalStateException();
    }
  }
  */

  /*
  protected void onCompleted(HeadersAmp headers,
                             ActorAmp actor,
                             Object value)
  {
    throw new UnsupportedOperationException(getClass().getName());
  }

  protected void onFailed(HeadersAmp headers,
                          ActorAmp actor,
                          Throwable exn)
  {
    throw new UnsupportedOperationException(getClass().getName());
  }
  */

  @Override
  public String toString()
  {
    return (getClass().getSimpleName()
             + "[" + getMethod().name()
             + ",to=" + getInboxTarget().serviceRef().address()
             + ",state=" + getState()
             + "]");
  }
  
  /*
  private class ChainResultFunAsyncQuery<U,V> extends ChainResultFunAsync<U,V>
  {
    ChainResultFunAsyncQuery(Result<V> result, Function<U,V> fun)
    {
      super(result, fun);
    }
    
  }
  */
  
  public enum State {
    QUERY {
      @Override
      State toSent() { return SENT; }

      @Override
      WorkerDeliver getWorker(QueryMessageBase<?> query)
      {
        return query.getInboxTarget().getWorker();
      }

      @Override
      void offerQueue(QueryMessageBase<?> query, long timeout)
      {
        try {
          query.getInboxTarget().offer(query, timeout);
        } catch (Throwable e) {
          query.fail(e);
        }
      }
      
      @Override
      void offer(QueryMessageBase<?> query, long timeout)
      {
        try {
          query.offerQuery(timeout);
        } catch (Throwable e) {
          query.fail(e);
        }
      }
      
      @Override
      void fail(QueryMessageBase<?> query, Throwable exn)
      {
        query._replyError = exn;
        
        query._state = FAILED;  

        query.offerResult(query._timeout);
      }

      @Override
      void invoke(QueryMessageBase<?> query, InboxAmp inbox, ActorAmp actorDeliver)
      {
        query._state = SENT;
        
        try {
          query.invokeQuery(inbox, actorDeliver);
        } catch (Throwable e) {
          query.fail(e);
        }
      }
    },
    
    SENT {
      @Override
      State toComplete(QueryMessageBase<?> item) { return COMPLETE; }
      
      @Override
      State toFail(Throwable exn) { return FAILED; }

      @Override
      <T> void complete(QueryMessageBase<T> query, T value)
      {
        query._value = value;
        
        query._state = COMPLETE;  

        query.offerResult(query._timeout);
      }
      
      @Override
      void fail(QueryMessageBase<?> query, Throwable exn)
      {
        query._replyError = exn;
        
        query._state = FAILED;  

        query.offerResult(query._timeout);
      }

      @Override
      void invoke(QueryMessageBase<?> query, InboxAmp inbox, ActorAmp actorDeliver)
      {
        // XXX: for journal
        try {
          query.invokeQuery(inbox, actorDeliver);
        } catch (Throwable e) {
          query.fail(e);
        }
      }
    },
    
    COMPLETE {
      @Override
      boolean isReply() { return true; }

      @Override
      void offerQueue(QueryMessageBase<?> query, long timeout)
      {
        query.getInboxCaller().offerResult(query);
      }
      
      @Override
      void offer(QueryMessageBase<?> query, long timeout)
      {
        query.offerResult(timeout);
      }

      @Override
      void invoke(QueryMessageBase<?> query, InboxAmp inbox, ActorAmp actorDeliver)
      {
        if (query.invokeComplete(actorDeliver)) {
          query._state = CLOSED;
        }
      }
    },
    
    FAILED {
      @Override
      boolean isReply() { return true; }
      
      @Override
      void offerQueue(QueryMessageBase<?> query, long timeout)
      {
        query.getInboxCaller().offerResult(query);
      }
      
      @Override
      void offer(QueryMessageBase<?> query, long timeout)
      {
        query.offerResult(timeout);
      }

      @Override
      void invoke(QueryMessageBase<?> query, InboxAmp inbox, ActorAmp actorDeliver)
      {
        if (query.invokeFail(actorDeliver)) {
          query._state = CLOSED;
        }
      }
    },
    
    CLOSED {
      @Override
      <T> void complete(QueryMessageBase<T> query, T value)
      {
        // XXX: log because double complete might be error
        // throw new IllegalStateException(this + " " + query + " " + value);
      }
    };
    
    boolean isReply() { return false; }
    
    State toComplete(QueryMessageBase<?> query)
    {
      OutboxAmp outbox = OutboxAmp.current();
      System.err.println("OUTBOX: " + outbox
                         + " " + outbox.inbox());
      Thread.dumpStack();
      throw new IllegalStateException(this + " " + query);
    }
    
    State toFail(Throwable exn)
    { 
      throw new IllegalStateException(toString(), exn);
    }
    
    State toSent()
    { 
      throw new IllegalStateException(toString());
    }
    
    WorkerDeliver getWorker(QueryMessageBase<?> query)
    {
      return query.getInboxCaller().getWorker();
    }
    
    void offer(QueryMessageBase<?> query, long timeout)
    {
      throw new IllegalStateException(this+ " " + query);
    }
    
    void offerQueue(QueryMessageBase<?> query, long timeout)
    {
      throw new IllegalStateException(this+ " " + query);
    }
    
    <T> void complete(QueryMessageBase<T> query, T value)
    {
      throw new IllegalStateException(this + " " + query + " " + value);
    }
    
    void invoke(QueryMessageBase<?> query, InboxAmp inbox, ActorAmp actorDeliver)
    {
      throw new IllegalStateException(this + " " + query + " " + inbox + " " + actorDeliver + " " + System.identityHashCode(query));
    }
    
    void fail(QueryMessageBase<?> query, Throwable exn)
    {
      if (exn instanceof ServiceExceptionClosed) {
        log.finer(this + " unexpected " + exn);
      }
      else {
        log.log(Level.FINER, this + " unexpected " + exn, exn);
      }
    }
  }
}