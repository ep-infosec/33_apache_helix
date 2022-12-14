package org.apache.helix.tools;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.helix.BaseDataAccessor;
import org.apache.helix.ConfigAccessor;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixException;
import org.apache.helix.PropertyKey;
import org.apache.helix.PropertyKey.Builder;
import org.apache.helix.PropertyPathBuilder;
import org.apache.helix.TestHelper;
import org.apache.helix.zookeeper.api.client.RealmAwareZkClient;
import org.apache.helix.zookeeper.datamodel.ZNRecord;
import org.apache.helix.ZkUnitTestBase;
import org.apache.helix.cloud.azure.AzureConstants;
import org.apache.helix.cloud.constants.CloudProvider;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.manager.zk.ZKHelixDataAccessor;
import org.apache.helix.manager.zk.ZNRecordSerializer;
import org.apache.helix.manager.zk.ZkBaseDataAccessor;
import org.apache.helix.model.CloudConfig;
import org.apache.helix.model.ClusterConfig;
import org.apache.helix.model.HelixConfigScope.ConfigScopeProperty;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.LiveInstance;
import org.testng.Assert;
import org.testng.AssertJUnit;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestClusterSetup extends ZkUnitTestBase {
  protected static final String CLUSTER_NAME = "TestClusterSetup";
  protected static final String TEST_DB = "TestDB";
  protected static final String INSTANCE_PREFIX = "instance_";
  protected static final String STATE_MODEL = "MasterSlave";
  protected static final String TEST_NODE = "testnode_1";

  private ClusterSetup _clusterSetup;

  private static String[] createArgs(String str) {
    String[] split = str.split("[ ]+");
    System.out.println(Arrays.toString(split));
    return split;
  }

  @BeforeClass()
  public void beforeClass() throws Exception {
    System.out
        .println("START TestClusterSetup.beforeClass() " + new Date(System.currentTimeMillis()));
    _clusterSetup = new ClusterSetup(ZK_ADDR);
  }

  @AfterClass()
  public void afterClass() {
    deleteCluster(CLUSTER_NAME);
    _clusterSetup.close();
    System.out.println("END TestClusterSetup.afterClass() " + new Date(System.currentTimeMillis()));
  }

  @BeforeMethod()
  public void setup() {
    try {
      _gZkClient.deleteRecursively("/" + CLUSTER_NAME);
      _clusterSetup.addCluster(CLUSTER_NAME, true);
    } catch (Exception e) {
      System.out.println("@BeforeMethod TestClusterSetup exception:" + e);
    }
  }

  private boolean testZkAdminTimeoutHelper() {
    boolean exceptionThrown = false;
    ZKHelixAdmin admin = null;
    try {
      admin = new ZKHelixAdmin("localhost:27999");
    } catch (Exception e) {
      exceptionThrown = true;
    } finally {
      if (admin != null) {
        admin.close();
      }
    }
    return  exceptionThrown;
  }

  // Note, with mvn 3.6.1, we have a nasty bug that running "mvn test" under helix-core,
  // all the bellow test will be invoked after other test including @AfterClass cleanup of this
  // This bug does not happen of running command as "mvn test -Dtest=TestClusterSetup". Nor does it
  // happen in intellij. The workaround found is to add dependsOnMethods attribute to all the rest.
  @Test(dependsOnMethods = "testAddClusterWithValidCloudConfig")
  public void testZkAdminTimeout() {
    boolean exceptionThrown = testZkAdminTimeoutHelper();

    Assert.assertTrue(exceptionThrown);
    System.setProperty(ZKHelixAdmin.CONNECTION_TIMEOUT, "3");
    exceptionThrown = testZkAdminTimeoutHelper();
    long time = System.currentTimeMillis();

    Assert.assertTrue(exceptionThrown);
    Assert.assertTrue(System.currentTimeMillis() - time < 5000);
  }

  @Test(dependsOnMethods = "testAddClusterWithValidCloudConfig")
  public void testAddInstancesToCluster() throws Exception {
    String[] instanceAddresses = new String[3];
    for (int i = 0; i < 3; i++) {
      String currInstance = INSTANCE_PREFIX + i;
      instanceAddresses[i] = currInstance;
    }
    String nextInstanceAddress = INSTANCE_PREFIX + 3;

    _clusterSetup.addInstancesToCluster(CLUSTER_NAME, instanceAddresses);

    // verify instances
    for (String instance : instanceAddresses) {
      verifyInstance(_gZkClient, CLUSTER_NAME, instance, true);
    }

    _clusterSetup.addInstanceToCluster(CLUSTER_NAME, nextInstanceAddress);
    verifyInstance(_gZkClient, CLUSTER_NAME, nextInstanceAddress, true);
    // re-add
    boolean caughtException = false;
    try {
      _clusterSetup.addInstanceToCluster(CLUSTER_NAME, nextInstanceAddress);
    } catch (HelixException e) {
      caughtException = true;
    }
    AssertJUnit.assertTrue(caughtException);
  }

  @Test(dependsOnMethods = "testAddClusterWithValidCloudConfig")
  public void testDisableDropInstancesFromCluster() throws Exception {
    testAddInstancesToCluster();
    String[] instanceAddresses = new String[3];
    for (int i = 0; i < 3; i++) {
      String currInstance = INSTANCE_PREFIX + i;
      instanceAddresses[i] = currInstance;
    }
    String nextInstanceAddress = INSTANCE_PREFIX + 3;

    boolean caughtException = false;
    // drop without disabling
    try {
      _clusterSetup.dropInstanceFromCluster(CLUSTER_NAME, nextInstanceAddress);
    } catch (HelixException e) {
      caughtException = true;
    }
    AssertJUnit.assertTrue(caughtException);

    // disable
    _clusterSetup.getClusterManagementTool().enableInstance(CLUSTER_NAME, nextInstanceAddress,
        false);
    verifyEnabled(_gZkClient, CLUSTER_NAME, nextInstanceAddress, false);

    // drop
    _clusterSetup.dropInstanceFromCluster(CLUSTER_NAME, nextInstanceAddress);
    verifyInstance(_gZkClient, CLUSTER_NAME, nextInstanceAddress, false);

    // re-drop
    caughtException = false;
    try {
      _clusterSetup.dropInstanceFromCluster(CLUSTER_NAME, nextInstanceAddress);
    } catch (HelixException e) {
      caughtException = true;
    }
    AssertJUnit.assertTrue(caughtException);

    // bad format disable, drop
    String badFormatInstance = "badinstance";
    caughtException = false;
    try {
      _clusterSetup.getClusterManagementTool().enableInstance(CLUSTER_NAME, badFormatInstance,
          false);
    } catch (HelixException e) {
      caughtException = true;
    }
    AssertJUnit.assertTrue(caughtException);

    caughtException = false;
    try {
      _clusterSetup.dropInstanceFromCluster(CLUSTER_NAME, badFormatInstance);
    } catch (HelixException e) {
      caughtException = true;
    }
    AssertJUnit.assertTrue(caughtException);
  }

  @Test(dependsOnMethods = "testAddClusterWithValidCloudConfig")
  public void testAddResource() throws Exception {
    try {
      _clusterSetup.addResourceToCluster(CLUSTER_NAME, TEST_DB, 16, STATE_MODEL);
    } catch (Exception ignored) {
    }
    verifyResource(_gZkClient, CLUSTER_NAME, TEST_DB, true);
  }

  @Test(dependsOnMethods = "testAddClusterWithValidCloudConfig")
  public void testRemoveResource() throws Exception {
    _clusterSetup.setupTestCluster(CLUSTER_NAME);
    verifyResource(_gZkClient, CLUSTER_NAME, TEST_DB, true);
    _clusterSetup.dropResourceFromCluster(CLUSTER_NAME, TEST_DB);
    verifyResource(_gZkClient, CLUSTER_NAME, TEST_DB, false);
  }

  @Test(dependsOnMethods = "testAddClusterWithValidCloudConfig")
  public void testRebalanceCluster() throws Exception {
    _clusterSetup.setupTestCluster(CLUSTER_NAME);
    // testAddInstancesToCluster();
    testAddResource();
    _clusterSetup.rebalanceStorageCluster(CLUSTER_NAME, TEST_DB, 4);
    verifyReplication(_gZkClient, CLUSTER_NAME, TEST_DB, 4);
  }

  @Test(dependsOnMethods = "testAddClusterWithValidCloudConfig")
  public void testParseCommandLinesArgs() throws Exception {
    // wipe ZK
    _gZkClient.deleteRecursively("/" + CLUSTER_NAME);

    ClusterSetup
        .processCommandLineArgs(createArgs("-zkSvr " + ZK_ADDR + " --addCluster " + CLUSTER_NAME));

    // wipe again
    _gZkClient.deleteRecursively("/" + CLUSTER_NAME);

    _clusterSetup.setupTestCluster(CLUSTER_NAME);

    ClusterSetup.processCommandLineArgs(
        createArgs("-zkSvr " + ZK_ADDR + " --addNode " + CLUSTER_NAME + " " + TEST_NODE));
    verifyInstance(_gZkClient, CLUSTER_NAME, TEST_NODE, true);
    try {
      ClusterSetup.processCommandLineArgs(createArgs("-zkSvr " + ZK_ADDR + " --addResource "
          + CLUSTER_NAME + " " + TEST_DB + " 4 " + STATE_MODEL));
    } catch (Exception ignored) {
    }
    verifyResource(_gZkClient, CLUSTER_NAME, TEST_DB, true);
    // ClusterSetup
    // .processCommandLineArgs(createArgs("-zkSvr "+ZK_ADDR+" --addNode node-1"));
    ClusterSetup.processCommandLineArgs(createArgs(
        "-zkSvr " + ZK_ADDR + " --enableInstance " + CLUSTER_NAME + " " + TEST_NODE + " true"));
    verifyEnabled(_gZkClient, CLUSTER_NAME, TEST_NODE, true);

    ClusterSetup.processCommandLineArgs(createArgs(
        "-zkSvr " + ZK_ADDR + " --enableInstance " + CLUSTER_NAME + " " + TEST_NODE + " false"));
    verifyEnabled(_gZkClient, CLUSTER_NAME, TEST_NODE, false);
    ClusterSetup.processCommandLineArgs(
        createArgs("-zkSvr " + ZK_ADDR + " --dropNode " + CLUSTER_NAME + " " + TEST_NODE));
  }

  @Test(dependsOnMethods = "testAddClusterWithValidCloudConfig")
  public void testSetGetRemoveParticipantConfig() throws Exception {
    String className = TestHelper.getTestClassName();
    String methodName = TestHelper.getTestMethodName();
    String clusterName = className + "_" + methodName;

    System.out.println("START " + clusterName + " at " + new Date(System.currentTimeMillis()));

    _clusterSetup.addCluster(clusterName, true);
    _clusterSetup.addInstanceToCluster(clusterName, "localhost_0");

    // test set/get/remove instance configs
    String scopeArgs = clusterName + ",localhost_0";
    String keyValueMap = "key1=value1,key2=value2";
    String keys = "key1,key2";
    ClusterSetup.processCommandLineArgs(new String[] {
        "--zkSvr", ZK_ADDR, "--setConfig", ConfigScopeProperty.PARTICIPANT.toString(), scopeArgs,
        keyValueMap
    });

    // getConfig returns json-formatted key-value pairs
    String valuesStr = _clusterSetup.getConfig(ConfigScopeProperty.PARTICIPANT, scopeArgs, keys);
    ZNRecordSerializer serializer = new ZNRecordSerializer();
    ZNRecord record = (ZNRecord) serializer.deserialize(valuesStr.getBytes());
    Assert.assertEquals(record.getSimpleField("key1"), "value1");
    Assert.assertEquals(record.getSimpleField("key2"), "value2");

    ClusterSetup.processCommandLineArgs(new String[] {
        "--zkSvr", ZK_ADDR, "--removeConfig", ConfigScopeProperty.PARTICIPANT.toString(), scopeArgs,
        keys
    });
    valuesStr = _clusterSetup.getConfig(ConfigScopeProperty.PARTICIPANT, scopeArgs, keys);
    record = (ZNRecord) serializer.deserialize(valuesStr.getBytes());
    Assert.assertNull(record.getSimpleField("key1"));
    Assert.assertNull(record.getSimpleField("key2"));

    deleteCluster(clusterName);
    System.out.println("END " + clusterName + " at " + new Date(System.currentTimeMillis()));
  }

  @Test(dependsOnMethods = "testAddClusterWithValidCloudConfig")
  public void testEnableCluster() throws Exception {
    // Logger.getRootLogger().setLevel(Level.INFO);
    String className = TestHelper.getTestClassName();
    String methodName = TestHelper.getTestMethodName();
    String clusterName = className + "_" + methodName;

    System.out.println("START " + clusterName + " at " + new Date(System.currentTimeMillis()));

    TestHelper.setupCluster(clusterName, ZK_ADDR, 12918, // participant port
        "localhost", // participant name prefix
        "TestDB", // resource name prefix
        1, // resources
        10, // partitions per resource
        5, // number of nodes
        3, // replicas
        "MasterSlave", true); // do rebalance

    // pause cluster
    ClusterSetup.processCommandLineArgs(new String[] {
        "--zkSvr", ZK_ADDR, "--enableCluster", clusterName, "false"
    });

    Builder keyBuilder = new Builder(clusterName);
    boolean exists = _gZkClient.exists(keyBuilder.pause().getPath());
    Assert.assertTrue(exists, "pause node under controller should be created");

    // resume cluster
    ClusterSetup.processCommandLineArgs(new String[] {
        "--zkSvr", ZK_ADDR, "--enableCluster", clusterName, "true"
    });

    exists = _gZkClient.exists(keyBuilder.pause().getPath());
    Assert.assertFalse(exists, "pause node under controller should be removed");

    // clean up
    TestHelper.dropCluster(clusterName, _gZkClient);

    System.out.println("END " + clusterName + " at " + new Date(System.currentTimeMillis()));
  }

  @Test(dependsOnMethods = "testAddClusterWithValidCloudConfig")
  public void testDropInstance() throws Exception {
    // drop without stop, should throw exception
    String className = TestHelper.getTestClassName();
    String methodName = TestHelper.getTestMethodName();
    String clusterName = className + "_" + methodName;
    String instanceAddress = "localhost:12918";
    String instanceName = "localhost_12918";

    System.out.println("START " + clusterName + " at " + new Date(System.currentTimeMillis()));

    TestHelper.setupCluster(clusterName, ZK_ADDR, 12918, // participant port
        "localhost", // participant name prefix
        "TestDB", // resource name prefix
        1, // resources
        10, // partitions per resource
        5, // number of nodes
        3, // replicas
        "MasterSlave", true); // do rebalance

    // add fake liveInstance
    HelixDataAccessor accessor = new ZKHelixDataAccessor(clusterName,
        new ZkBaseDataAccessor.Builder<ZNRecord>()
            .setRealmMode(RealmAwareZkClient.RealmMode.SINGLE_REALM)
            .setZkClientType(ZkBaseDataAccessor.ZkClientType.DEDICATED)
            .setZkAddress(ZK_ADDR)
            .build());

    try {
      Builder keyBuilder = new Builder(clusterName);
      LiveInstance liveInstance = new LiveInstance(instanceName);
      liveInstance.setSessionId("session_0");
      liveInstance.setHelixVersion("version_0");
      Assert.assertTrue(accessor.setProperty(keyBuilder.liveInstance(instanceName), liveInstance));

      // Drop instance without stopping the live instance, should throw HelixException
      try {
        ClusterSetup.processCommandLineArgs(
            new String[]{"--zkSvr", ZK_ADDR, "--dropNode", clusterName, instanceAddress});
        Assert.fail("Should throw exception since localhost_12918 is still in LIVEINSTANCES/");
      } catch (HelixException expected) {
        Assert.assertEquals(expected.getMessage(),
            "Cannot drop instance " + instanceName + " as it is still live. Please stop it first");
      }
      accessor.removeProperty(keyBuilder.liveInstance(instanceName));

      // drop without disable, should throw exception
      try {
        ClusterSetup.processCommandLineArgs(
            new String[]{"--zkSvr", ZK_ADDR, "--dropNode", clusterName, instanceAddress});
        Assert.fail("Should throw exception since " + instanceName + " is enabled");
      } catch (HelixException expected) {
        Assert.assertEquals(expected.getMessage(),
            "Node " + instanceName + " is enabled, cannot drop");
      }

      // Disable the instance
      ClusterSetup.processCommandLineArgs(
          new String[]{"--zkSvr", ZK_ADDR, "--enableInstance", clusterName, instanceName, "false"});
      // Drop the instance
      ClusterSetup.processCommandLineArgs(
          new String[]{"--zkSvr", ZK_ADDR, "--dropNode", clusterName, instanceAddress});

      Assert.assertNull(accessor.getProperty(keyBuilder.instanceConfig(instanceName)),
          "Instance config should be dropped");
      Assert.assertFalse(_gZkClient.exists(PropertyPathBuilder.instance(clusterName, instanceName)),
          "Instance/host should be dropped");
    } finally {
      // Have to close the dedicated zkclient in accessor to avoid zkclient leakage.
      accessor.getBaseDataAccessor().close();
      TestHelper.dropCluster(clusterName, _gZkClient);

      // Verify the cluster has been dropped.
      Assert.assertTrue(TestHelper.verify(() -> {
        if (_gZkClient.exists("/" + clusterName)) {
          TestHelper.dropCluster(clusterName, _gZkClient);
        }
        return true;
      }, TestHelper.WAIT_DURATION));
    }

    System.out.println("END " + clusterName + " at " + new Date(System.currentTimeMillis()));
  }

  @Test(dependsOnMethods = "testAddClusterWithValidCloudConfig")
  public void testDisableResource() throws Exception {
    String className = TestHelper.getTestClassName();
    String methodName = TestHelper.getTestMethodName();
    String clusterName = className + "_" + methodName;
    System.out.println("START " + clusterName + " at " + new Date(System.currentTimeMillis()));
    TestHelper.setupCluster(clusterName, ZK_ADDR, 12918, // participant port
        "localhost", // participant name prefix
        "TestDB", // resource name prefix
        1, // resources
        10, // partitions per resource
        5, // number of nodes
        3, // replicas
        "MasterSlave", true); // do rebalance
    // disable "TestDB0" resource
    ClusterSetup.processCommandLineArgs(new String[] {
        "--zkSvr", ZK_ADDR, "--enableResource", clusterName, "TestDB0", "false"
    });
    BaseDataAccessor<ZNRecord> baseAccessor = new ZkBaseDataAccessor<ZNRecord>(ZK_ADDR);
    HelixDataAccessor accessor = new ZKHelixDataAccessor(clusterName, baseAccessor);
    PropertyKey.Builder keyBuilder = accessor.keyBuilder();
    IdealState idealState = accessor.getProperty(keyBuilder.idealStates("TestDB0"));
    Assert.assertFalse(idealState.isEnabled());
    // enable "TestDB0" resource
    ClusterSetup.processCommandLineArgs(new String[] {
        "--zkSvr", ZK_ADDR, "--enableResource", clusterName, "TestDB0", "true"
    });
    idealState = accessor.getProperty(keyBuilder.idealStates("TestDB0"));
    Assert.assertTrue(idealState.isEnabled());

    TestHelper.dropCluster(clusterName, _gZkClient);
    System.out.println("END " + clusterName + " at " + new Date(System.currentTimeMillis()));
  }

  @Test(expectedExceptions = HelixException.class)
  public void testAddClusterWithInvalidCloudConfig() throws Exception {
    String className = TestHelper.getTestClassName();
    String methodName = TestHelper.getTestMethodName();
    String clusterName = className + "_" + methodName;

    CloudConfig.Builder cloudConfigInitBuilder = new CloudConfig.Builder();
    cloudConfigInitBuilder.setCloudEnabled(true);
    List<String> sourceList = new ArrayList<String>();
    sourceList.add("TestURL");
    cloudConfigInitBuilder.setCloudInfoSources(sourceList);
    cloudConfigInitBuilder.setCloudProvider(CloudProvider.CUSTOMIZED);

    CloudConfig cloudConfigInit = cloudConfigInitBuilder.build();

    // Since setCloudInfoProcessorName is missing, this add cluster call will throw an exception
    _clusterSetup.addCluster(clusterName, false, cloudConfigInit);
  }

  @Test(dependsOnMethods = "testAddClusterWithInvalidCloudConfig")
  public void testAddClusterWithValidCloudConfig() throws Exception {
    String className = TestHelper.getTestClassName();
    String methodName = TestHelper.getTestMethodName();
    String clusterName = className + "_" + methodName;

    CloudConfig.Builder cloudConfigInitBuilder = new CloudConfig.Builder();
    cloudConfigInitBuilder.setCloudEnabled(true);
    cloudConfigInitBuilder.setCloudID("TestID");
    List<String> sourceList = new ArrayList<String>();
    sourceList.add("TestURL");
    cloudConfigInitBuilder.setCloudInfoSources(sourceList);
    cloudConfigInitBuilder.setCloudInfoProcessorName("TestProcessorName");
    cloudConfigInitBuilder.setCloudProvider(CloudProvider.CUSTOMIZED);

    CloudConfig cloudConfigInit = cloudConfigInitBuilder.build();

    _clusterSetup.addCluster(clusterName, false, cloudConfigInit);

    // Read CloudConfig from Zookeeper and check the content
    ConfigAccessor _configAccessor = new ConfigAccessor(_gZkClient);
    CloudConfig cloudConfigFromZk = _configAccessor.getCloudConfig(clusterName);
    Assert.assertTrue(cloudConfigFromZk.isCloudEnabled());
    Assert.assertEquals(cloudConfigFromZk.getCloudID(), "TestID");
    List<String> listUrlFromZk = cloudConfigFromZk.getCloudInfoSources();
    Assert.assertEquals(listUrlFromZk.get(0), "TestURL");
    Assert.assertEquals(cloudConfigFromZk.getCloudInfoProcessorName(), "TestProcessorName");
    Assert.assertEquals(cloudConfigFromZk.getCloudProvider(), CloudProvider.CUSTOMIZED.name());
  }

  @Test(dependsOnMethods = "testAddClusterWithInvalidCloudConfig",
      expectedExceptions = HelixException.class)
  public void testAddClusterWithFailure() throws Exception {
    HelixAdmin admin = mock(HelixAdmin.class);
    when(admin.addCluster(anyString(), anyBoolean())).thenReturn(Boolean.FALSE);
    String className = TestHelper.getTestClassName();
    String methodName = TestHelper.getTestMethodName();
    String clusterName = className + "_" + methodName;

    CloudConfig.Builder cloudConfigInitBuilder = new CloudConfig.Builder();
    cloudConfigInitBuilder.setCloudEnabled(true);
    List<String> sourceList = new ArrayList<String>();
    sourceList.add("TestURL");
    cloudConfigInitBuilder.setCloudInfoSources(sourceList);
    cloudConfigInitBuilder.setCloudProvider(CloudProvider.CUSTOMIZED);

    CloudConfig cloudConfigInit = cloudConfigInitBuilder.build();

    // Since setCloudInfoProcessorName is missing, this add cluster call will throw an exception
    _clusterSetup.addCluster(clusterName, false, cloudConfigInit);
  }

  @Test(dependsOnMethods = "testAddClusterWithValidCloudConfig")
  public void testAddClusterAzureProvider() throws Exception {
    String className = TestHelper.getTestClassName();
    String methodName = TestHelper.getTestMethodName();
    String clusterName = className + "_" + methodName;

    CloudConfig.Builder cloudConfigInitBuilder = new CloudConfig.Builder();
    cloudConfigInitBuilder.setCloudEnabled(true);
    cloudConfigInitBuilder.setCloudID("TestID");
    cloudConfigInitBuilder.setCloudProvider(CloudProvider.AZURE);

    CloudConfig cloudConfigInit = cloudConfigInitBuilder.build();

    _clusterSetup.addCluster(clusterName, false, cloudConfigInit);

    // Read CloudConfig from Zookeeper and check the content
    ConfigAccessor _configAccessor = new ConfigAccessor(ZK_ADDR);
    CloudConfig cloudConfigFromZk = _configAccessor.getCloudConfig(clusterName);
    Assert.assertTrue(cloudConfigFromZk.isCloudEnabled());
    Assert.assertEquals(cloudConfigFromZk.getCloudID(), "TestID");
    List<String> listUrlFromZk = cloudConfigFromZk.getCloudInfoSources();

    // Since it is Azure, topology information should have been populated.
    ClusterConfig clusterConfig = _configAccessor.getClusterConfig(clusterName);
    Assert.assertEquals(clusterConfig.getTopology(), AzureConstants.AZURE_TOPOLOGY);
    Assert.assertEquals(clusterConfig.getFaultZoneType(), AzureConstants.AZURE_FAULT_ZONE_TYPE);
    Assert.assertTrue(clusterConfig.isTopologyAwareEnabled());

    // Since provider is not customized, CloudInfoSources and CloudInfoProcessorName will be null.
    Assert.assertNull(listUrlFromZk);
    Assert.assertNull(cloudConfigFromZk.getCloudInfoProcessorName());
    Assert.assertEquals(cloudConfigFromZk.getCloudProvider(), CloudProvider.AZURE.name());
  }

  @Test(dependsOnMethods = "testAddClusterAzureProvider")
  public void testSetRemoveCloudConfig() throws Exception {
    String className = TestHelper.getTestClassName();
    String methodName = TestHelper.getTestMethodName();
    String clusterName = className + "_" + methodName;

    // Create Cluster without cloud config
    _clusterSetup.addCluster(clusterName, false);

    // Read CloudConfig from Zookeeper and check the content
    ConfigAccessor _configAccessor = new ConfigAccessor(ZK_ADDR);
    CloudConfig cloudConfigFromZk = _configAccessor.getCloudConfig(clusterName);
    Assert.assertNull(cloudConfigFromZk);

    String cloudConfigManifest =
        "{\"simpleFields\" : {\"CLOUD_ENABLED\" : \"true\",\"CLOUD_PROVIDER\": \"AZURE\"}}\"";
    _clusterSetup.setCloudConfig(clusterName, cloudConfigManifest);

    // Read cloud config from ZK and make sure the fields are accurate
    cloudConfigFromZk = _configAccessor.getCloudConfig(clusterName);
    Assert.assertNotNull(cloudConfigFromZk);
    Assert.assertEquals(CloudProvider.AZURE.name(), cloudConfigFromZk.getCloudProvider());
    Assert.assertTrue(cloudConfigFromZk.isCloudEnabled());

    // Remove cloud config and make sure it has been removed
    _clusterSetup.removeCloudConfig(clusterName);
    cloudConfigFromZk = _configAccessor.getCloudConfig(clusterName);
    Assert.assertNull(cloudConfigFromZk);
  }
}
