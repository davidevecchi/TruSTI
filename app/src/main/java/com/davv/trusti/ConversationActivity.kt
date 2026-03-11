package com.davv.trusti

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.davv.trusti.databinding.ActivityConversationBinding
import com.davv.trusti.model.Contact
import com.davv.trusti.model.Message
import com.davv.trusti.smp.P2PMessenger
import com.davv.trusti.utils.MessageStore
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConversationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConversationBinding
    private lateinit var contact: Contact
    private val messages = mutableListOf<Message>()
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contact = intent.getStringExtra(EXTRA_CONTACT)!!.let { json ->
            val o = JSONObject(json)
            Contact(
                name = o.getString("name"),
                publicKey = o.getString("publicKey"),
                lastSeen = o.getLong("lastSeen"),
                disambiguation = o.optString("disambiguation").takeIf { it.isNotEmpty() }
            )
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = contact.name
            setDisplayHomeAsUpEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener { finish() }

        messages.addAll(MessageStore.load(this, contact.publicKey))
        val adapter = MessageAdapter()
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@ConversationActivity).apply { stackFromEnd = true }
            this.adapter = adapter
        }
        if (messages.isNotEmpty()) binding.rvMessages.scrollToPosition(messages.size - 1)

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            binding.etMessage.setText("")
            lifecycleScope.launch {
                P2PMessenger.get(this@ConversationActivity).sendMessage(contact, text)
            }
        }

        P2PMessenger.get(this).messageFlow
            .onEach { msg ->
                if (msg.contactPublicKey == contact.publicKey) {
                    messages.add(msg)
                    adapter.notifyItemInserted(messages.size - 1)
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                }
            }
            .launchIn(lifecycleScope)
    }

    private inner class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(pos: Int) = if (messages[pos].isOutbound) VIEW_OUT else VIEW_IN

        override fun getItemCount() = messages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == VIEW_OUT) R.layout.item_message_out else R.layout.item_message_in
            return object : RecyclerView.ViewHolder(
                LayoutInflater.from(parent.context).inflate(layout, parent, false)
            ) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
            val msg = messages[pos]
            holder.itemView.findViewById<TextView>(R.id.tvContent).text = msg.content
            holder.itemView.findViewById<TextView>(R.id.tvTime).text = fmt.format(Date(msg.timestamp))
        }
    }

    companion object {
        private const val EXTRA_CONTACT = "contact_json"
        private const val VIEW_IN = 0
        private const val VIEW_OUT = 1

        fun start(context: Context, contact: Contact) {
            val json = JSONObject().apply {
                put("name", contact.name)
                put("publicKey", contact.publicKey)
                put("lastSeen", contact.lastSeen)
                put("disambiguation", contact.disambiguation ?: "")
            }.toString()
            context.startActivity(
                Intent(context, ConversationActivity::class.java)
                    .putExtra(EXTRA_CONTACT, json)
            )
        }
    }
}
