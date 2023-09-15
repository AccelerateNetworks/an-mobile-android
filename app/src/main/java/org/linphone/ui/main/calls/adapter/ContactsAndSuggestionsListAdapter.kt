package org.linphone.ui.main.calls.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.linphone.R
import org.linphone.databinding.CallSuggestionListCellBinding
import org.linphone.databinding.ContactListCellBinding
import org.linphone.ui.main.calls.model.ContactOrSuggestionModel
import org.linphone.utils.Event
import org.linphone.utils.HeaderAdapter

class ContactsAndSuggestionsListAdapter(
    private val viewLifecycleOwner: LifecycleOwner
) : ListAdapter<ContactOrSuggestionModel, RecyclerView.ViewHolder>(
    ContactOrSuggestionDiffCallback()
),
    HeaderAdapter {
    companion object {
        private const val CONTACT_TYPE = 0
        private const val SUGGESTION_TYPE = 1
    }

    var selectedAdapterPosition = -1

    val contactClickedEvent: MutableLiveData<Event<ContactOrSuggestionModel>> by lazy {
        MutableLiveData<Event<ContactOrSuggestionModel>>()
    }

    override fun displayHeaderForPosition(position: Int): Boolean {
        val model = getItem(position)
        if (model.friend == null) {
            if (position == 0) {
                return true
            }
            val previousModel = getItem(position - 1)
            return previousModel.friend != null
        }
        return false
    }

    override fun getHeaderViewForPosition(context: Context, position: Int): View {
        return LayoutInflater.from(context).inflate(R.layout.call_suggestion_list_decoration, null)
    }

    override fun getItemViewType(position: Int): Int {
        val model = getItem(position)
        return if (model.friend == null) SUGGESTION_TYPE else CONTACT_TYPE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            CONTACT_TYPE -> {
                val binding: ContactListCellBinding = DataBindingUtil.inflate(
                    LayoutInflater.from(parent.context),
                    R.layout.contact_list_cell,
                    parent,
                    false
                )
                ContactViewHolder(binding)
            }
            else -> {
                val binding: CallSuggestionListCellBinding = DataBindingUtil.inflate(
                    LayoutInflater.from(parent.context),
                    R.layout.call_suggestion_list_cell,
                    parent,
                    false
                )
                SuggestionViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (getItemViewType(position)) {
            CONTACT_TYPE -> (holder as ContactViewHolder).bind(getItem(position))
            else -> (holder as SuggestionViewHolder).bind(getItem(position))
        }
    }

    inner class ContactViewHolder(
        val binding: ContactListCellBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        @UiThread
        fun bind(contactOrSuggestionModel: ContactOrSuggestionModel) {
            with(binding) {
                model = contactOrSuggestionModel.contactAvatarModel

                lifecycleOwner = viewLifecycleOwner

                binding.root.isSelected = bindingAdapterPosition == selectedAdapterPosition

                binding.setOnClickListener {
                    contactClickedEvent.value = Event(contactOrSuggestionModel)
                }

                executePendingBindings()
            }
        }
    }

    inner class SuggestionViewHolder(
        val binding: CallSuggestionListCellBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        @UiThread
        fun bind(contactOrSuggestionModel: ContactOrSuggestionModel) {
            with(binding) {
                model = contactOrSuggestionModel

                lifecycleOwner = viewLifecycleOwner

                binding.root.isSelected = bindingAdapterPosition == selectedAdapterPosition

                binding.setOnClickListener {
                    contactClickedEvent.value = Event(contactOrSuggestionModel)
                }

                executePendingBindings()
            }
        }
    }

    private class ContactOrSuggestionDiffCallback : DiffUtil.ItemCallback<ContactOrSuggestionModel>() {
        override fun areItemsTheSame(
            oldItem: ContactOrSuggestionModel,
            newItem: ContactOrSuggestionModel
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: ContactOrSuggestionModel,
            newItem: ContactOrSuggestionModel
        ): Boolean {
            return false
        }
    }
}
