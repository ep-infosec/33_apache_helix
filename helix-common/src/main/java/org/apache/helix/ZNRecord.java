package org.apache.helix;

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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * @deprecated
 * Please use {@link org.apache.helix.zookeeper.datamodel.ZNRecord}
 * in zookeeper-api instead.
 * <p>
 * Generic Record Format to store data at a Node This can be used to store
 * simpleFields mapFields listFields
 */
@Deprecated
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)
public class ZNRecord extends org.apache.helix.zookeeper.datamodel.ZNRecord {
  public ZNRecord(String id) {
    super(id);
  }

  public ZNRecord(org.apache.helix.zookeeper.datamodel.ZNRecord record) {
    super(record);
  }

  public ZNRecord(org.apache.helix.zookeeper.datamodel.ZNRecord record, String id) {
    super(record, id);
  }
}
