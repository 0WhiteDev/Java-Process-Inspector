package uk.whitedev.injector;

import uk.whitedev.utils.ProcessUtil;

import javax.swing.*;
import java.awt.*;

public class InjectorGui {
    private final DLLInjector injector = new DLLInjector();
    private final Color backgroundColor = new Color(50, 50, 50);
    private final Color textColor = Color.WHITE;
    private final Color buttonColor = new Color(80, 80, 80);
    private final Color buttonTextColor = Color.WHITE;

    public void runInjectorGui() {
        new Thread(this::createAndShowGUI).start();
    }

    private void createAndShowGUI(){
        JFrame frame = new JFrame("DLL Injector [Java Process Inspector]");
        JPanel jPanel = new JPanel(new GridLayout(5, 1, 10, 10));
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(310, 300);
        jPanel.setBackground(backgroundColor);


        JLabel pidLabel = new JLabel("PID:");
        JTextField pidField = new JTextField(ProcessUtil.getProcessPid());
        JLabel dllLabel = new JLabel("DLL Path:");
        JTextField dllField = new JTextField();
        JButton injectButton = new JButton("Inject!");

        pidLabel.setForeground(textColor);
        dllLabel.setForeground(textColor);
        pidField.setBackground(backgroundColor);
        pidField.setForeground(textColor);
        dllField.setBackground(backgroundColor);
        dllField.setForeground(textColor);
        injectButton.setBackground(buttonColor);
        injectButton.setForeground(buttonTextColor);

        injectButton.addActionListener(e -> {
            if(injector.injectDLL(Long.parseLong(pidField.getText()), dllField.getText())){
                JOptionPane.showMessageDialog(frame, "The dll file was injected successfully", "Success", JOptionPane.INFORMATION_MESSAGE);
            }else{
                JOptionPane.showMessageDialog(frame, "Can't inject this dll file into the specified process!", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });

        jPanel.add(pidLabel);
        jPanel.add(pidField);
        jPanel.add(dllLabel);
        jPanel.add(dllField);
        jPanel.add(injectButton);
        frame.add(jPanel);

        frame.setVisible(true);
    }
}
