"use client";

import React from 'react';

interface ComparisonModalProps {
  onClose: () => void;
}

const ComparisonModal: React.FC<ComparisonModalProps> = ({ onClose }) => {
  return (
    <div className="fixed inset-0 bg-slate-900/25 backdrop-blur-sm flex items-center justify-center z-50">
      <div className="bg-background p-6 rounded-lg shadow-lg max-w-lg w-full relative">
        <h2 className="text-xl font-semibold mb-4 text-foreground">Comparison Details</h2>
        <p className="text-foreground">This is a placeholder for the comparison modal content.</p>
        <button
          onClick={onClose}
          className="mt-4 px-4 py-2 bg-primary text-primary-foreground rounded-md hover:bg-primary/90"
        >
          Close
        </button>
      </div>
    </div>
  );
};

export default ComparisonModal;