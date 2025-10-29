package io.github.dorumrr.de1984.ui.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding

/**
 * Base Fragment class that handles ViewBinding lifecycle
 * 
 * Usage:
 * ```
 * class MyFragment : BaseFragment<FragmentMyBinding>() {
 *     override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?) =
 *         FragmentMyBinding.inflate(inflater, container, false)
 *     
 *     override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
 *         super.onViewCreated(view, savedInstanceState)
 *         // Use binding here
 *         binding.myTextView.text = "Hello"
 *     }
 * }
 * ```
 */
abstract class BaseFragment<VB : ViewBinding> : Fragment() {

    protected var _binding: VB? = null

    /**
     * Access to the binding object. Only valid between onCreateView and onDestroyView.
     */
    protected val binding get() = _binding!!

    /**
     * Implement this to provide the ViewBinding for this fragment
     */
    abstract fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): VB

    /**
     * Override this to implement scroll to top behavior when fragment is shown
     */
    open fun scrollToTop() {
        // Default implementation does nothing
        // Override in fragments that need scroll-to-top behavior
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = getViewBinding(inflater, container)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

