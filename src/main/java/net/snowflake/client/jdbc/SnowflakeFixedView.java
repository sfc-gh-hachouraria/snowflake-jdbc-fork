/*
 * Copyright (c) 2012-2017 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.client.jdbc;

import java.util.List;

/**
 * An interface to use for returning query results from any java class
 *
 * @author jhuang
 */
public interface SnowflakeFixedView
{
  public List<SnowflakeColumnMetadata> describeColumns() throws Exception;

  public List<Object> getNextRow() throws Exception;
}