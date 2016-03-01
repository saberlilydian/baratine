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
 *   Free Software Foundation, Inc.
 *   59 Temple Place, Suite 330
 *   Boston, MA 02111-1307  USA
 *
 * @author Scott Ferguson
 */

package com.caucho.v5.kraken.query;

import io.baratine.service.Result;

import java.util.Objects;

import com.caucho.v5.kraken.table.TableManagerKraken;

public class CreateQuery extends QueryKraken
{
  private TableBuilderKraken _tableBuilder;
  private TableManagerKraken _tableManager;
  
  public CreateQuery(TableManagerKraken tableManager,
                     TableBuilderKraken tableBuilder,
                     String sql)
  {
    super(sql);
    
    Objects.requireNonNull(tableManager);
    Objects.requireNonNull(tableBuilder);
    
    _tableManager = tableManager;
    _tableBuilder = tableBuilder;
  }

  @Override
  public void exec(Result<Object> result,
                   Object ...params)
  {
    Objects.requireNonNull(result);
    
    _tableBuilder.create(_tableManager, result);
  }

  public void restoreTable()
  {
    _tableBuilder.create(_tableManager, Result.ignore());
  }
}