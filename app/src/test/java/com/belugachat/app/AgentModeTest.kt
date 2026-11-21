package com.belugachat.app

import com.belugachat.app.chat.AgentMode
import com.belugachat.app.chat.ChatEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModeTest {
    @Test fun detectsGoalAndResearchCommands() {
        assertEquals(AgentMode.GOAL, AgentMode.detect("/goal ship the thing"))
        assertEquals(AgentMode.GOAL, AgentMode.detect("  /GOAL ship it"))
        assertEquals(AgentMode.DEEPSEARCH, AgentMode.detect("/deepsearch compare phones"))
        assertEquals(AgentMode.DEEPSEARCH, AgentMode.detect("/research quantum computing"))
        assertEquals(AgentMode.CHAT, AgentMode.detect("please /goal this later"))
        assertEquals(AgentMode.CHAT, AgentMode.detect("hello"))
    }

    @Test fun autonomousModesGetMoreRoundsAndInstructions() {
        assertTrue(AgentMode.isAutonomous(AgentMode.GOAL))
        assertTrue(AgentMode.isAutonomous(AgentMode.DEEPSEARCH))
        assertEquals(24, AgentMode.maxRounds(AgentMode.GOAL))
        assertEquals(16, AgentMode.maxRounds(AgentMode.DEEPSEARCH))
        assertEquals(ChatEngine.MAX_ROUNDS, AgentMode.maxRounds(AgentMode.CHAT))
        assertTrue(AgentMode.instructions(AgentMode.CHAT) == null)
        assertTrue(AgentMode.instructions(AgentMode.GOAL)!!.contains("autonomous goal"))
        assertTrue(AgentMode.instructions(AgentMode.DEEPSEARCH)!!.contains("deep research"))
    }

    @Test fun labelsAndCommandRemoval() {
        assertEquals("Goal mode", AgentMode.label(AgentMode.GOAL))
        assertEquals("Deep research", AgentMode.label(AgentMode.DEEPSEARCH))
        assertNull(AgentMode.label(AgentMode.CHAT))
        assertEquals("plan the launch", AgentMode.removeCommand("/goal plan the launch"))
        assertEquals("compare phones", AgentMode.removeCommand("  /deepsearch   compare phones"))
        assertEquals("", AgentMode.removeCommand("/goal"))
    }
}
