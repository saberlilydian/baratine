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
 * @author Alex Rojkov
 */

package com.caucho.v5.amp.vault;

import java.util.concurrent.atomic.AtomicLong;

import com.caucho.v5.util.CurrentTime;
import com.caucho.v5.util.Primes;
import com.caucho.v5.util.RandomUtil;

import io.baratine.service.IdAsset;

public final class IdAssetGenerator
{
  private long _node;
    
  private int _timeOffset;
  private int _nodeBits = 10; 
  private int _sequenceBits;
  private int _sequencePrime;
  
  private long _sequenceMask;
  
  private AtomicLong _sequence = new AtomicLong();
  
  public IdAssetGenerator(int nodeIndex, int nodeCount)
  {
    _nodeBits = 32 - Integer.numberOfLeadingZeros(nodeCount);
    _node = nodeIndex;
    
    _timeOffset = 64 - IdAsset.TIME_BITS;
    _sequenceBits = 64 - IdAsset.TIME_BITS - _nodeBits;
    _sequenceMask = (1L << _sequenceBits) - 1;
    
    //_sequencePrime = Math.max(1, Primes.getBiggestPrime(_sequenceMask >> 1));
    _sequencePrime = 287093;
  }

  public long get()
  {
    long now = CurrentTime.getCurrentTime() / 1000;
    
    long oldSequence;
    long newSequence;
    
    do {
      oldSequence = _sequence.get();
      
      long oldTime = oldSequence >>> _timeOffset;
    
      if (oldTime != now) {
        newSequence = ((now << _timeOffset)
                      + (RandomUtil.getRandomLong() & _sequenceMask));
      }
      else {
        // relatively prime increment will use the whole sequence space
        newSequence = oldSequence + _sequencePrime;
      }
    } while (! _sequence.compareAndSet(oldSequence, newSequence));
      
    long id = ((now << _timeOffset)
               | (_node << _sequenceBits)
               | (newSequence & _sequenceMask));
      
    return id;
  }
}