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

package com.caucho.v5.http.protocol;

import java.util.ArrayList;
import java.util.Objects;

import com.caucho.v5.io.TempBuffer;
import com.caucho.v5.network.port.ConnectionTcp;

import io.baratine.service.AfterBatch;

/**
 * Handles a HTTP connection.
 */
class OutHttpProxyImpl implements OutHttpProxy
{
  private ConnectionHttp _connHttp;
  
  private ArrayList<Pending> _pendingList = new ArrayList<>();
  
  private boolean _isClose;
  
  OutHttpProxyImpl(ConnectionHttp conn)
  {
    Objects.requireNonNull(conn);
    _connHttp = conn;
  }
  
  private ConnectionTcp conn()
  {
    return _connHttp.connTcp();
  }
  
  @Override
  public void writeFirst(OutHttp out, 
                         TempBuffer buffer, 
                         long length, 
                         boolean isEnd)
  {
    if (out.canWrite(_connHttp.sequenceWrite() + 1)) {
      _isClose = out.writeFirst(conn().writeStream(), buffer, length, isEnd);
      
      if (isEnd) {
        writePending();
      }
    }
    else {
      _pendingList.add(new PendingFirst(out, buffer, length, isEnd));
    }
  }

  @Override
  public void writeNext(OutHttp out, TempBuffer buffer, boolean isEnd)
  {
    if (out.canWrite(_connHttp.sequenceWrite() + 1)) {
      _isClose = out.writeNext(conn().writeStream(), buffer, isEnd);
      
      if (isEnd) {
        writePending();
      }
    }
    else {
      _pendingList.add(new PendingNext(out, buffer, isEnd));
    }
  }
  
  private void writePending()
  {
    boolean isEnd;
    
    do {
      isEnd = false;
      
      for (int i = 0; i < _pendingList.size(); i++) {
        Pending pending = _pendingList.get(i);
        
        if (pending.out().canWrite(_connHttp.sequenceWrite() + 1)) {
          pending.write();
          
          if (pending.isEnd()) {
            isEnd = true;
          }
          
          _pendingList.remove(i--);
        }
      }
    } while (isEnd);
  }

  @Override
  public void disconnect(OutHttp out)
  {
    out.disconnect(conn().writeStream());
  }
  
  @AfterBatch
  public void afterBatch()
  {
    try {
      if (_isClose) {
        conn().writeStream().close();
      }
      else {
        conn().writeStream().flush();
      }
    } catch (Exception e) {
      e.printStackTrace();
    } finally {
      _connHttp.onFlush();
    }
  }
  
  abstract private class Pending
  {
    private OutHttp _out;
    private boolean _isEnd;
    
    Pending(OutHttp out, boolean isEnd)
    {
      _out = out;
      _isEnd = isEnd;
    }
    
    OutHttp out()
    {
      return _out;
    }
    
    boolean isEnd()
    {
      return _isEnd;
    }
    
    abstract void write(); 
  }
  
  private class PendingFirst extends Pending
  {
    private TempBuffer _head;
    private long _length;
    private boolean _isEnd;
    
    PendingFirst(OutHttp out,
                 TempBuffer head,
                 long length,
                 boolean isEnd)
    {
      super(out, isEnd);

      _head = head;
      _length = length;
    }
    
    @Override
    void write()
    {
      out().writeFirst(conn().writeStream(), _head, _length, isEnd());
    }
  }
  
  private class PendingNext extends Pending
  {
    private TempBuffer _head;
    
    PendingNext(OutHttp out,
                TempBuffer head,
                boolean isEnd)
    {
      super(out, isEnd);

      _head = head;
    }
    
    @Override
    void write()
    {
      out().writeNext(conn().writeStream(), _head, isEnd());
    }
  }
}