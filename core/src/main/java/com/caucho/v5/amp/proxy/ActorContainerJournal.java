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

package com.caucho.v5.amp.proxy;

import io.baratine.service.Cancel;
import io.baratine.service.Result;
import io.baratine.service.ServiceManager;
import io.baratine.service.ServiceRef;
import io.baratine.timer.TimerService;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.caucho.v5.amp.actor.SaveResult;
import com.caucho.v5.amp.spi.ActorAmp;

/**
 * Baratine actor container for children.
 */
public class ActorContainerJournal extends ActorContainerBase
{
  private boolean _isReplay = true;
  private ServiceRef _serviceSelf;
  
  private AtomicBoolean _isCheckpointStarted = new AtomicBoolean();
  
  private long _journalDelay;
  private TimerService _timerService;
  private Consumer<Cancel> _startSaveRef;
  
  private boolean _isActive;
  
  public ActorContainerJournal(String path, long journalDelay)
  {
    super(path);
    
    _journalDelay = journalDelay;
  }
  
  @Override
  public boolean isJournalReplay()
  {
    return _isReplay;
  }
  
  /*
  // @Override
  public boolean isJournal()
  {
    return true;
  }
  */
  
  @Override
  public void onActive()
  {
    _isReplay = false;
    
    if (_isActive) {
      return;
    }
    _isActive = true;
    
    _serviceSelf = ServiceRef.current();
    
    if (_journalDelay > 0) {
      ServiceManager manager = _serviceSelf.manager();
      
      _timerService = manager.service("timer:").as(TimerService.class);
      Consumer<Cancel> startSave = h->startSave();
      
      _startSaveRef = (Consumer<Cancel>) _serviceSelf.pin(startSave).as(Consumer.class);
    }
    
    super.onActive();
    
    startSave();
  }
  
  @Override
  public void afterBatch(ActorAmp actor)
  {
    if (isSaveRequired()
        && _serviceSelf != null
        && _isCheckpointStarted.compareAndSet(false, true)) {
      if (_journalDelay > 0) {
        _timerService.runAfter(_startSaveRef,
                               _journalDelay, TimeUnit.MILLISECONDS, 
                               Result.ignore());
      }
      else {
        startSave();
      }
    }
  }
  
  private void startSave()
  {
    _serviceSelf.save(Result.ignore());
  }

  @Override
  public void onSave(SaveResult saveResult)
  {
    _isCheckpointStarted.compareAndSet(true, false);
    
    super.onSave(saveResult);
  }
}